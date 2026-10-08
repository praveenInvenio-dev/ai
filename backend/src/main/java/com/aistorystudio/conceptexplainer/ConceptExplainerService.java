package com.aistorystudio.conceptexplainer;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.ImageGenerationProvider;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Concept Explainer v3.
 *
 * <pre>
 * topic -> Ollama lesson plan (one TEMPLATE per scene + narration as ordered sentences)
 *       -> ConceptSlideRenderer draws each scene as a neon infographic panel (exact text/code/tables),
 *          AI image only for the real-world object in analogy scenes
 *       -> narration spoken sentence by sentence, so we KNOW when each sentence starts
 *       -> build step k of the slide fades in when the sentence that explains it starts
 *       -> scenes joined, soft music ducked under the voice, 1920x1080
 * </pre>
 * No Ken Burns zoom/pan: it softens text and code; the synced reveal is the motion.
 */
@Service
public class ConceptExplainerService {

    private static final Logger log = LoggerFactory.getLogger(ConceptExplainerService.class);
    private static final double FADE = 0.35;          // element fade-in
    private static final double SENTENCE_GAP = 0.28;  // breath between sentences
    private static final double SCENE_TAIL = 0.7;     // silence before next scene
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?।॥])\\s+");

    private final ProviderGateway gateway;
    private final StorageProvider storage;
    private final MediaProcessor media;
    private final ConceptExplainerJobStore jobs;
    private final ConceptSlideRenderer renderer = new ConceptSlideRenderer();
    private final ObjectMapper mapper = new ObjectMapper();

    public ConceptExplainerService(ProviderGateway gateway, StorageProvider storage, MediaProcessor media,
                                   ConceptExplainerJobStore jobs) {
        this.gateway = gateway;
        this.storage = storage;
        this.media = media;
        this.jobs = jobs;
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String motion, String model) {
        return create(topic, instructions, language, duration, difficulty, motion, model, "GENERAL", "General", false);
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String motion, String model, String track, String subject, boolean examFocus) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("Enter a topic to explain.");
        String m = motion == null ? "REVEAL" : motion.trim().toUpperCase(Locale.ROOT);
        if (!m.equals("STATIC")) m = "REVEAL";
        String t = blank(track) ? "GENERAL" : track.trim().toUpperCase(Locale.ROOT);
        if (!List.of("TECHNOLOGY", "JEE", "NEET", "GENERAL").contains(t)) t = "GENERAL";
        String sub = blank(subject) ? (t.equals("JEE") ? "Physics" : t.equals("NEET") ? "Biology" : "General") : subject.trim();
        boolean exam = examFocus && (t.equals("JEE") || t.equals("NEET"));
        return jobs.create(topic.trim(), instructions == null ? "" : instructions.trim(),
                blank(language) ? "English" : language.trim(), blank(duration) ? "1 minute" : duration.trim(),
                blank(difficulty) ? "Complete Beginner" : difficulty.trim(), m, model == null ? "" : model.trim(), t, sub, exam);
    }

    // =================================================================== generate

    @Async("videoGenerationExecutor")
    public void generateAsync(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            job.setStatus(ConceptExplainerJob.Status.PLANNING);
            job.setStage("Writing the lesson plan");
            JsonNode plan = plan(job);
            job.setTitle(text(plan, "title", job.getTopic()));
            job.setSummary(text(plan, "summary", "A visual lesson on " + job.getTopic()));
            JsonNode scenes = plan.path("scenes");
            job.getScenes().clear();
            for (int i = 0; i < scenes.size(); i++) {
                ConceptExplainerJob.Scene s = new ConceptExplainerJob.Scene();
                JsonNode n = scenes.get(i);
                s.setSceneNumber(i + 1);
                s.setTemplate(template(n));
                s.setTitle(text(n, "title", "Scene " + (i + 1)));
                s.setSentences(sentences(n.path("narration")));
                s.setNarration(String.join(" ", s.getSentences()));
                s.setCode(text(n, "code", null));
                s.setIllustrationPrompt(text(n, "illustration", null));
                s.setPlanJson(n.toString());
                job.getScenes().add(s);
            }

            job.setStatus(ConceptExplainerJob.Status.GENERATING_SCENES);
            for (ConceptExplainerJob.Scene s : job.getScenes()) {
                String base = "Scene " + s.getSceneNumber() + "/" + job.getScenes().size() + " - " + s.getTitle();
                if (needsIllustration(s)) {
                    job.setStage(base + ": drawing the real-world illustration");
                    makeIllustration(job, s);
                }
                job.setStage(base + ": drawing the slide");
                renderSlide(job, s);
                job.setStage(base + ": recording the narration");
                makeNarration(job, s);
                job.setStage(base + ": syncing the slide to the voice");
                buildClip(job, s);
            }
            job.setStatus(ConceptExplainerJob.Status.ASSEMBLING);
            assemble(job);
            job.setStage("Ready");
            job.setStatus(ConceptExplainerJob.Status.SUCCEEDED);
        } catch (Exception e) {
            log.warn("Concept explainer {} failed: {}", id, e.getMessage(), e);
            job.setErrorMessage(rootMessage(e));
            job.setStage("Generation failed");
            job.setStatus(ConceptExplainerJob.Status.FAILED);
        }
    }

    // =================================================================== regenerate

    /** Synchronous checks + flips the status, so a double click cannot start two redos. */
    public synchronized void regenerateScene(UUID id, int sceneNumber, String kind) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Lesson not found (it may have expired).");
        if (sceneNumber < 1 || sceneNumber > job.getScenes().size()) throw new IllegalArgumentException("Invalid scene number.");
        if (job.getStatus() != ConceptExplainerJob.Status.SUCCEEDED) {
            throw new IllegalStateException(job.getStatus() == ConceptExplainerJob.Status.FAILED
                    ? "This lesson did not finish. Use 'Retry lesson' instead."
                    : "Wait until the lesson is finished before changing a scene.");
        }
        job.setStatus(ConceptExplainerJob.Status.REGENERATING);
        job.setStage("Queued: scene " + sceneNumber + " " + kind);
    }

    @Async("videoGenerationExecutor")
    public void regenerateSceneAsync(UUID id, int sceneNumber, String kind) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            ConceptExplainerJob.Scene s = job.getScenes().get(sceneNumber - 1);
            if ("image".equals(kind) || "both".equals(kind)) {
                job.setStage("Scene " + sceneNumber + ": new illustration");
                if (needsIllustration(s)) makeIllustration(job, s);
                renderSlide(job, s);
            }
            if ("audio".equals(kind) || "both".equals(kind)) {
                job.setStage("Scene " + sceneNumber + ": new narration");
                makeNarration(job, s);
            }
            job.setStage("Scene " + sceneNumber + ": syncing");
            buildClip(job, s);
            assemble(job);
            job.setStage("Ready");
            job.setStatus(ConceptExplainerJob.Status.SUCCEEDED);
        } catch (Exception e) {
            job.setErrorMessage(rootMessage(e));
            job.setStage("Regeneration failed");
            job.setStatus(ConceptExplainerJob.Status.SUCCEEDED); // previous video is still valid
        }
    }

    /** A failed lesson: start over with the same settings. */
    public ConceptExplainerJob retry(UUID id) {
        ConceptExplainerJob old = jobs.get(id);
        if (old == null) throw new IllegalArgumentException("Lesson not found (it may have expired).");
        return create(old.getTopic(), old.getInstructions(), old.getLanguage(), old.getDuration(), old.getDifficulty(),
                old.getMotion(), old.getModel(), old.getTrack(), old.getSubject(), old.isExamFocus());
    }

    // =================================================================== plan

    private JsonNode plan(ConceptExplainerJob job) {
        boolean deep = job.isDeepDive();
        int minWords = deep ? 380 : 125, maxWords = deep ? 440 : 155;
        String user = userPrompt(job, deep);
        JsonNode plan = readJson(gateway.llm().generateStructured(SYSTEM, user, blankToNull(job.getModel())));
        validate(plan);
        int words = countWords(plan);
        // One correction round if the lesson is clearly too short / too long for the chosen mode.
        if (words < minWords * 0.75 || words > maxWords * 1.3) {
            job.setStage("Adjusting the lesson length (" + words + " words, target " + minWords + "-" + maxWords + ")");
            String fix = user + "\n\nYour previous plan had " + words + " spoken words in total. The target is "
                    + minWords + "-" + maxWords + ". Return the COMPLETE plan again in the same JSON format, "
                    + (words < minWords ? "adding scenes/sentences with real teaching value" : "removing the least important scenes/sentences")
                    + ". Previous plan:\n" + plan;
            try {
                JsonNode second = readJson(gateway.llm().generateStructured(SYSTEM, fix, blankToNull(job.getModel())));
                validate(second);
                int w2 = countWords(second);
                if (Math.abs(w2 - (minWords + maxWords) / 2) < Math.abs(words - (minWords + maxWords) / 2)) {
                    plan = second;
                    words = w2;
                }
            } catch (Exception e) {
                job.getWarnings().add("Length correction failed: " + e.getMessage());
            }
        }
        job.setWordCount(words);
        if (words < minWords * 0.75 || words > maxWords * 1.3) {
            job.getWarnings().add("Lesson has " + words + " spoken words (target " + minWords + "-" + maxWords + "), so it will run "
                    + (words < minWords ? "short" : "long") + ".");
        }
        return plan;
    }

    private static final String SYSTEM = """
            You are the chief instructional designer for a premium visual learning product.
            Turn the topic into a BEST-IN-CLASS mini lesson a complete beginner understands after one viewing.
            Accuracy is mandatory.

            TEACHING
            - Start with a curiosity hook or a familiar problem; build a simple mental model before jargon.
            - ONE idea per scene. Explain why the concept exists, not just what it is.
            - Use natural day-to-day analogies, Indian context where it fits (kirana shelf, Swiggy/Zomato order,
              UPI/bank balance, petrol pump, parking, train ticket, tiffin box ...). Do not reuse one analogy everywhere.
            - Technical topics: small, CORRECT code; explain every important part. Never invent syntax.
            - Include one common beginner mistake. End with a memorable recap.
            - Warm, simple, conversational - an excellent teacher talking to a smart friend. No filler.
            - For JEE/NEET topics, prioritize scientifically/mathematically correct reasoning, standard notation, units and exam-relevant misconceptions. Never fabricate a fact, formula, derivation, reaction, diagram relationship or answer.
            - For exam-focused lessons, include a small checkpoint/question only when it can be answered unambiguously from the lesson.

            SCENES ARE NEON INFOGRAPHIC SLIDES (one panel each, like a premium neon technical whiteboard).
            The app draws every slide itself from your data, so text and code are always crisp and correct.
            Pick ONE template per scene and fill ONLY its fields:
            - "definition": text, box {label, value}, callouts [3 x {label, detail}]  (e.g. Box=Variable, Label=Name, Content=Value)
            - "analogy": text, illustration, callouts [2-3 x {label, detail}], caption
                 illustration = ONE real-world object to draw, e.g. "water bottle on a supermarket shelf with a price tag"
            - "analogy_code": text, illustration, code (3-6 lines)
            - "code_anatomy": code (ONE line), parts [2-4 x {token (exact substring of code), label, detail}]
            - "table": columns [2-3], rows [3-6 x [cells]]
            - "code_visual": text, code (1-3 lines), box {label, value}, after {label, value} ONLY when a value changes, caption
            - "code_block": text (optional), code (3-8 lines)
            - "example_list": text (optional), items [3-5 x {icon, code, label}]
                 icon is one of: bank, fuel, calendar, parking, cart, phone, wallet, bag, clock, home, bulb, car, book, money, chart, lock, cloud, box, ticket, food
            - "checklist": items [3-6 x {ok: true|false, text}]
            - "summary": statement, mappings [2-4 x {left, right}], formula, formulaResult
            Non-programming topics: use code/formula fields for short formulas or facts (e.g. "speed = distance / time"),
            or prefer definition / analogy / table / example_list / checklist / summary.
            Keep slide text SHORT (text <= 25 words, labels <= 4 words) - the narration carries the detail.
            Slide text and narration in the requested language; code stays in its original syntax.
            Typical order: hook/definition -> analogy -> code_anatomy or example -> details -> mistake (checklist) -> summary.

            NARRATION
            "narration" is an ARRAY of 2-5 short spoken sentences IN THE ORDER the slide builds up:
            sentence 1 introduces the slide, later sentences explain the next element (next callout, part, row, item).
            The app reveals each slide element when its sentence starts, so order matters.

            Return JSON only:
            {"title":"...","summary":"...","scenes":[{"template":"...","title":"...","narration":["...","..."], ...template fields}]}
            """;

    private String userPrompt(ConceptExplainerJob job, boolean deep) {
        return "Learning track: " + job.getTrack()
                + "\nSubject: " + job.getSubject()
                + "\nExam focus: " + job.isExamFocus()
                + "\nTopic: " + job.getTopic()
                + "\nLanguage: " + job.getLanguage()
                + "\nDifficulty: " + job.getDifficulty()
                + "\nUser instructions: " + (job.getInstructions().isBlank()
                ? "Teach from zero using the simplest language, strong everyday analogies and memorable examples." : job.getInstructions())
                + "\n" + trackInstruction(job)
                + "\n" + (deep
                ? "3-minute Deep Dive: 18-22 scenes, 380-440 spoken words in total (about 175 seconds). Build intuition, explain the mechanism, several real examples, one beginner pitfall, recap + a tiny checkpoint question with its answer."
                : "1-minute Quick Learn: 9-11 scenes, 125-155 spoken words in total (about 60 seconds). Core mental model, one or two great examples, one concrete code/example, memorable recap. No advanced edge cases.");
    }

    private String trackInstruction(ConceptExplainerJob job) {
        String t = job.getTrack();
        if ("JEE".equals(t)) {
            return "JEE TEACHING MODE: Teach for competitive-exam understanding, not rote memorization. For Physics use intuition -> diagram/free-body diagram -> law/formula -> units/meaning -> worked numerical -> common trap. For Mathematics use concept -> formula/identity -> worked example -> shortcut only when mathematically valid -> common trap. For Chemistry use particle-level intuition -> equation/formula -> application -> common exam trap. Use standard school/JEE terminology and SI units. Never invent a formula, derivation, answer, or syllabus claim. If a derivation is too long for the chosen duration, explain the key reasoning instead of faking steps."
                    + (job.isExamFocus() ? " EXAM FOCUS IS ON: include one JEE-style mini question/checkpoint and explain why the correct answer is correct; avoid trick questions unless the trick is genuinely educational." : "");
        }
        if ("NEET".equals(t)) {
            return "NEET TEACHING MODE: Teach for medical-entrance understanding with clear diagrams/processes. For Biology use structure -> function -> process/sequence -> key fact -> common misconception -> recall checkpoint. For Physics use intuition -> diagram -> formula -> units -> worked numerical -> common trap. For Chemistry use molecular/particle intuition -> reaction/equation -> application -> common trap. Keep facts precise and distinguish must-remember facts from explanation. Never invent NCERT facts, reactions, equations, values, or syllabus claims."
                    + (job.isExamFocus() ? " EXAM FOCUS IS ON: include one NEET-style mini question/checkpoint with a concise explanation of the answer." : "");
        }
        if ("TECHNOLOGY".equals(t)) {
            return "TECHNOLOGY TEACHING MODE: Teach from first principles with a real-world mental model, then a small correct example/code, then practical usage and one beginner mistake. Prefer production-relevant examples without unnecessary framework jargon.";
        }
        return "GENERAL LEARNING MODE: Teach the idea from zero using the clearest mental model, a concrete everyday example, and a short recap. Adapt diagrams, formulas, facts, or examples to the topic.";
    }

    private void validate(JsonNode plan) {
        if (!plan.path("scenes").isArray() || plan.path("scenes").isEmpty()) {
            throw new IllegalStateException("The lesson planner returned no scenes.");
        }
    }

    private int countWords(JsonNode plan) {
        int words = 0;
        for (JsonNode s : plan.path("scenes")) for (String sentence : sentences(s.path("narration"))) words += sentence.split("\\s+").length;
        return words;
    }

    private String template(JsonNode n) {
        String t = text(n, "template", "").toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (ConceptSlideRenderer.TEMPLATES.contains(t)) return t;
        if (n.hasNonNull("code")) return "code_block";
        return "definition";
    }

    private List<String> sentences(JsonNode narration) {
        List<String> out = new ArrayList<>();
        if (narration.isArray()) {
            for (JsonNode n : narration) if (!n.asText("").isBlank()) out.add(clean(n.asText()));
        } else if (!narration.asText("").isBlank()) {
            for (String s : SENTENCE.split(narration.asText().trim())) if (!s.isBlank()) out.add(clean(s));
        }
        if (out.isEmpty()) out.add("...");
        return out;
    }

    // =================================================================== slide

    private boolean needsIllustration(ConceptExplainerJob.Scene s) {
        return ("analogy".equals(s.getTemplate()) || "analogy_code".equals(s.getTemplate()))
                && s.getIllustrationPrompt() != null && !s.getIllustrationPrompt().isBlank();
    }

    /** Only the real-world object is AI-generated: neon line art on pure black, no text, no people. */
    private void makeIllustration(ConceptExplainerJob job, ConceptExplainerJob.Scene s) {
        String prompt = "A single " + s.getIllustrationPrompt() + ", drawn as premium glowing neon line art with subtle 3D depth, "
                + "clean crisp outlines in electric cyan, magenta, yellow and green, isolated on a PURE BLACK background, centered, "
                + "high detail, like an icon in a futuristic neon technical infographic. No text, no letters, no numbers, no words, "
                + "no people, no hands, no faces, no frame, no border, no background scene.";
        String negative = "text, letters, words, numbers, typography, watermark, logo, people, person, hands, face, character, "
                + "background scenery, room, frame, border, collage, grid, multiple panels, photo, blurry, low contrast, clutter";
        try {
            var result = gateway.generateImage(new ImageGenerationProvider.ImageGenerationRequest(
                    prompt, negative, 1024, 1024, 28, 4.2, null, "qwen-image-2-1-16gb-t2i", null, null, null));
            Path p = storage.store(dir(job) + "/illustrations/scene-" + num(s) + "-v" + (s.getImageVersion() + 1) + ".png", result.imageBytes());
            s.setIllustrationPath(p.toString());
        } catch (Exception e) {
            job.getWarnings().add("Scene " + s.getSceneNumber() + ": illustration failed (" + rootMessage(e) + "), drew a simple icon instead.");
            s.setIllustrationPath(null);
        }
    }

    private void renderSlide(ConceptExplainerJob job, ConceptExplainerJob.Scene s) throws IOException {
        BufferedImage ill = null;
        if (s.getIllustrationPath() != null) {
            try { ill = ImageIO.read(Path.of(s.getIllustrationPath()).toFile()); } catch (Exception ignored) { }
        }
        ConceptSlideRenderer.Slide slide = toSlide(mapper.readTree(s.getPlanJson()), s, ill);
        List<BufferedImage> steps = renderer.render(slide);
        s.bumpImageVersion();
        List<String> paths = new ArrayList<>();
        for (int k = 0; k < steps.size(); k++) {
            Path p = storage.resolve(dir(job) + "/slides/scene-" + num(s) + "-v" + s.getImageVersion() + "-step" + k + ".png");
            Files.createDirectories(p.getParent());
            ImageIO.write(steps.get(k), "png", p.toFile());
            paths.add(p.toString());
        }
        s.setStepPaths(paths);
    }

    private ConceptSlideRenderer.Slide toSlide(JsonNode n, ConceptExplainerJob.Scene s, BufferedImage ill) {
        List<ConceptSlideRenderer.Callout> callouts = new ArrayList<>();
        for (JsonNode c : n.path("callouts")) callouts.add(new ConceptSlideRenderer.Callout(text(c, "label", ""), text(c, "detail", "")));
        List<ConceptSlideRenderer.CodePart> parts = new ArrayList<>();
        for (JsonNode c : n.path("parts")) parts.add(new ConceptSlideRenderer.CodePart(text(c, "token", null), text(c, "label", ""), text(c, "detail", "")));
        List<String> columns = new ArrayList<>();
        for (JsonNode c : n.path("columns")) columns.add(c.asText(""));
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode r : n.path("rows")) {
            List<String> row = new ArrayList<>();
            if (r.isArray()) for (JsonNode c : r) row.add(c.asText(""));
            else if (r.isObject()) r.fields().forEachRemaining(e -> row.add(e.getValue().asText("")));
            rows.add(row);
        }
        List<ConceptSlideRenderer.Item> items = new ArrayList<>();
        for (JsonNode c : n.path("items")) {
            String icon = text(c, "icon", "box").toLowerCase(Locale.ROOT);
            items.add(new ConceptSlideRenderer.Item(ConceptSlideRenderer.ICONS.contains(icon) ? icon : "box", text(c, "code", null),
                    text(c, "label", null), c.has("ok") ? c.path("ok").asBoolean(true) : null, text(c, "text", text(c, "label", null))));
        }
        List<ConceptSlideRenderer.Mapping> mappings = new ArrayList<>();
        for (JsonNode c : n.path("mappings")) mappings.add(new ConceptSlideRenderer.Mapping(text(c, "left", ""), text(c, "right", "")));
        return new ConceptSlideRenderer.Slide(s.getSceneNumber(), s.getTemplate(), s.getTitle(), text(n, "text", null),
                text(n, "caption", null), text(n, "code", null), callouts, parts, columns, rows, items,
                box(n.path("box")), n.hasNonNull("after") ? box(n.path("after")) : null,
                text(n, "statement", null), mappings, text(n, "formula", null), text(n, "formulaResult", null), ill);
    }

    private ConceptSlideRenderer.BoxSpec box(JsonNode b) {
        if (b == null || b.isMissingNode() || b.isNull()) return null;
        return new ConceptSlideRenderer.BoxSpec(text(b, "label", ""), text(b, "value", ""));
    }

    // =================================================================== narration (per sentence = exact timing)

    private void makeNarration(ConceptExplainerJob job, ConceptExplainerJob.Scene s) throws Exception {
        Path work = storage.resolve(dir(job) + "/audio/work-" + num(s));
        Files.createDirectories(work);
        List<Path> parts = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < s.getSentences().size(); i++) {
            var audio = gateway.synthesizeForStoryLanguage(new TextToSpeechProvider.TtsRequest(
                    s.getSentences().get(i), null, job.getLanguage(), 1.0, 1.0,
                    "friendly", 0.55, "clear conversational teacher", List.of(), false, null,
                    "Explain naturally, warmly and simply.", null));
            if (audio.providerWarning() != null && job.getWarnings().stream().noneMatch(w -> w.startsWith("Voice:"))) {
                job.getWarnings().add("Voice: " + audio.providerWarning());
            }
            Path p = work.resolve("s" + i + ".wav");
            Files.write(p, audio.audioBytes());
            parts.add(p);
            starts.add(t);
            double d = media.probeDurationSeconds(p);
            if (d <= 0) d = audio.durationSeconds();
            t += d + (i < s.getSentences().size() - 1 ? SENTENCE_GAP : 0);
        }
        // join sentences with short breaths, then the shared voice polish (length unchanged)
        Path joined = work.resolve("joined.wav");
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        for (Path p : parts) args.addAll(List.of("-i", p.toString()));
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            g.append('[').append(i).append(":a]aresample=48000,aformat=channel_layouts=stereo")
                    .append(i < parts.size() - 1 ? ",apad=pad_dur=" + fmt(SENTENCE_GAP) : "").append("[s").append(i).append("];");
        }
        for (int i = 0; i < parts.size(); i++) g.append("[s").append(i).append(']');
        g.append("concat=n=").append(parts.size()).append(":v=0:a=1[out]");
        args.addAll(List.of("-filter_complex", g.toString(), "-map", "[out]", joined.toString()));
        ffmpeg(args);
        byte[] wav = Files.readAllBytes(joined);
        try { wav = media.humanizeVoice(wav); } catch (Exception ignored) { }
        s.bumpAudioVersion();
        Path out = storage.store(dir(job) + "/audio/scene-" + num(s) + "-v" + s.getAudioVersion() + ".wav", wav);
        s.setAudioPath(out.toString());
        double speech = media.probeDurationSeconds(out);
        s.setDurationSeconds((speech > 0 ? speech : t) + SCENE_TAIL);
        s.setStepTimes(stepTimes(starts, s.getStepPaths().size(), speech > 0 ? speech : t, job));
    }

    /**
     * Build step k appears when the sentence that explains it starts: with at least as many sentences
     * as steps, element k appears with sentence k; otherwise spread proportionally. Each step stays >= 0.6 s.
     */
    static List<Double> stepTimes(List<Double> sentenceStarts, int steps, double speech, ConceptExplainerJob job) {
        List<Double> out = new ArrayList<>();
        if (steps <= 1 || (job != null && "STATIC".equals(job.getMotion()))) {
            out.add(0.0);
            return out;
        }
        int n = sentenceStarts.size();
        out.add(0.0);
        for (int k = 1; k < steps; k++) {
            double t;
            if (n >= steps) {
                // the planner writes one sentence per element: element k appears with sentence k
                t = sentenceStarts.get(k);
            } else if (n >= 2) {
                int j = (int) Math.round(k * n / (double) steps);
                j = Math.max(1, Math.min(n - 1, j));
                t = sentenceStarts.get(j);
                if (n < steps) { // fewer sentences than steps: spread inside the sentence
                    double next = j + 1 < n ? sentenceStarts.get(j + 1) : speech;
                    int sameSentence = 0;
                    for (int q = 1; q < k; q++) if (Math.max(1, Math.min(n - 1, (int) Math.round(q * n / (double) steps))) == j) sameSentence++;
                    t += (next - t) * Math.min(0.6, sameSentence * 0.35);
                }
            } else {
                t = speech * 0.8 * k / steps;
            }
            out.add(Math.max(t, out.get(k - 1) + 0.8));
        }
        // never reveal after the voice ends
        for (int k = 1; k < out.size(); k++) out.set(k, Math.min(out.get(k), Math.max(0.8 * k, speech - 0.6)));
        for (int k = 1; k < out.size(); k++) if (out.get(k) < out.get(k - 1) + 0.6) out.set(k, out.get(k - 1) + 0.6);
        return out;
    }

    // =================================================================== video

    /** One scene: build steps cross-fade in on their times, narration on top. */
    private void buildClip(ConceptExplainerJob job, ConceptExplainerJob.Scene s) throws Exception {
        if (s.getStepTimes() == null || s.getStepTimes().size() != s.getStepPaths().size()) {
            // slide re-rendered without new narration: keep timing rule consistent
            List<Double> starts = new ArrayList<>();
            double per = Math.max(0.5, (s.getDurationSeconds() - SCENE_TAIL) / Math.max(1, s.getSentences().size()));
            for (int i = 0; i < s.getSentences().size(); i++) starts.add(i * per);
            s.setStepTimes(stepTimes(starts, s.getStepPaths().size(), s.getDurationSeconds() - SCENE_TAIL, job));
        }
        double total = s.getDurationSeconds();
        List<String> steps = s.getStepPaths();
        List<Double> t = s.getStepTimes();
        int n = "STATIC".equals(job.getMotion()) ? 1 : steps.size();
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        StringBuilder f = new StringBuilder();
        if (n == 1) {
            args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(total), "-i", steps.get(steps.size() - 1)));
            f.append("[0:v]fps=30,format=yuv420p,setsar=1[v]");
        } else {
            for (int k = 0; k < n; k++) {
                double end = k + 1 < n ? t.get(k + 1) : total;
                double len = (end - t.get(k)) + (k == 0 ? 0 : FADE);
                if (k == 0) len = t.get(1);
                if (k == n - 1 && k > 0) len = total - t.get(k) + FADE;
                args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(len), "-i", steps.get(k)));
                f.append('[').append(k).append(":v]fps=30,format=yuv420p,setsar=1[i").append(k).append("];");
            }
            String prev = "i0";
            for (int k = 1; k < n; k++) {
                String out = k == n - 1 ? "v" : "x" + k;
                f.append('[').append(prev).append("][i").append(k).append("]xfade=transition=fade:duration=").append(fmt(FADE))
                        .append(":offset=").append(fmt(t.get(k) - FADE)).append('[').append(out).append("];");
                prev = out;
            }
            f.setLength(f.length() - 1);
        }
        int audioIndex = n;
        args.addAll(List.of("-i", s.getAudioPath()));
        f.append(";[").append(audioIndex).append(":a]aresample=48000,aformat=channel_layouts=stereo,apad[a]");
        Path out = storage.resolve(dir(job) + "/clips/scene-" + num(s) + "-i" + s.getImageVersion() + "-a" + s.getAudioVersion() + ".mp4");
        Files.createDirectories(out.getParent());
        args.addAll(List.of("-filter_complex", f.toString(), "-map", "[v]", "-map", "[a]", "-t", fmt(total),
                "-c:v", "libx264", "-preset", "medium", "-tune", "stillimage", "-crf", "17", "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", out.toString()));
        ffmpeg(args);
        s.setClipPath(out.toString());
    }

    /** Scenes back to back (no overlap, nothing cut), soft fade in/out, calm music ducked under the voice. */
    private void assemble(ConceptExplainerJob job) throws Exception {
        job.setStage("Joining scenes and mixing music");
        Path dir = storage.resolve(dir(job) + "/final");
        Files.createDirectories(dir);
        Path list = dir.resolve("clips.txt");
        StringBuilder sb = new StringBuilder();
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            if (s.getClipPath() == null) throw new IllegalStateException("Scene " + s.getSceneNumber() + " has no clip.");
            sb.append("file '").append(Path.of(s.getClipPath()).toAbsolutePath()).append("'\n");
        }
        Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
        Path joined = dir.resolve("joined.mp4");
        ffmpeg(List.of("ffmpeg", "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", joined.toString()));
        double total = media.probeDurationSeconds(joined);
        Path music = null;
        try { music = media.musicPresetPath("calm"); } catch (Exception ignored) { }
        Path out = dir.resolve("concept-explainer-" + (job.getVideoVersion() + 1) + ".mp4");
        String fadeOut = fmt(Math.max(0, total - 0.8));
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", joined.toString()));
        String filter;
        if (music != null && Files.isRegularFile(music)) {
            args.addAll(List.of("-stream_loop", "-1", "-i", music.toString()));
            filter = "[0:v]fade=t=in:st=0:d=0.5,fade=t=out:st=" + fadeOut + ":d=0.8[v];"
                    + "[1:a]aresample=48000,aformat=channel_layouts=stereo,volume=0.10[m];"
                    + "[0:a]asplit=2[voice][key];[m][key]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=450[duck];"
                    + "[voice][duck]amix=inputs=2:duration=first:normalize=0,afade=t=out:st=" + fadeOut + ":d=0.8[a]";
        } else {
            filter = "[0:v]fade=t=in:st=0:d=0.5,fade=t=out:st=" + fadeOut + ":d=0.8[v];[0:a]afade=t=out:st=" + fadeOut + ":d=0.8[a]";
        }
        args.addAll(List.of("-filter_complex", filter, "-map", "[v]", "-map", "[a]", "-t", fmt(total),
                "-c:v", "libx264", "-preset", "medium", "-tune", "stillimage", "-crf", "17", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
        ffmpeg(args);
        String previous = job.getVideoPath();
        job.setVideoPath(out.toString());
        job.setTotalDurationSeconds(media.probeDurationSeconds(out));
        if (previous != null && !previous.equals(out.toString())) {
            try { Files.deleteIfExists(Path.of(previous)); } catch (IOException ignored) { }
        }
    }

    // =================================================================== helpers

    private static void ffmpeg(List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg timed out");
        }
        if (p.exitValue() != 0) {
            String s = new String(out, StandardCharsets.UTF_8);
            throw new IllegalStateException("ffmpeg failed: " + (s.length() > 700 ? s.substring(s.length() - 700) : s));
        }
    }

    private JsonNode readJson(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            if (s.startsWith("```")) {
                s = s.replaceFirst("^```(json)?", "");
                int end = s.lastIndexOf("```");
                if (end >= 0) s = s.substring(0, end);
            }
            int a = s.indexOf('{'), b = s.lastIndexOf('}');
            if (a >= 0 && b > a) s = s.substring(a, b + 1);
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("The lesson planner returned invalid JSON: " + e.getMessage(), e);
        }
    }

    private static String clean(String narration) {
        return narration == null ? "" : narration.replaceAll("(?i)\\bscene\\s*\\d+\\s*[:.-]?", "").replaceAll("\\s+", " ").trim();
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return fallback;
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(x.asText("")));
            String joined = String.join("\n", parts).trim();
            return joined.isEmpty() ? fallback : joined;
        }
        String value = v.asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String dir(ConceptExplainerJob job) { return "concept-explainers/" + job.getId(); }

    private static String num(ConceptExplainerJob.Scene s) { return String.format(Locale.ROOT, "%02d", s.getSceneNumber()); }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) message = current.getMessage();
            current = current.getCause();
        }
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
