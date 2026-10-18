package com.aistorystudio.conceptexplainer;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.ImageGenerationProvider;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.StorageProvider;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.conceptexplainer.teaching.Destination;
import com.aistorystudio.conceptexplainer.teaching.LessonValidator;
import com.aistorystudio.conceptexplainer.teaching.SceneDraft;
import com.aistorystudio.conceptexplainer.teaching.SectionType;
import com.aistorystudio.conceptexplainer.teaching.TeachingPlanner;
import com.aistorystudio.conceptexplainer.teaching.TeachingStyle;
import com.aistorystudio.conceptexplainer.teaching.VoiceDirection;
import com.aistorystudio.conceptexplainer.tutorial.DiagramRenderer;
import com.aistorystudio.conceptexplainer.tutorial.DiagramSpec;
import com.aistorystudio.conceptexplainer.tutorial.ShortCaptions;
import com.aistorystudio.conceptexplainer.tutorial.NarrationScript;
import com.aistorystudio.conceptexplainer.tutorial.TutorialPrompts;
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
 *       -> generate a scene-specific illustration for every scene and compose it with exact text/code/diagram overlays,
 *          using the selected art direction and progressive explanatory reveals
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
    private static final double TUTORIAL_TAIL = 0.45; // reference video: short hand-over between scenes
    private static final int INTRO_FRAMES = 18;       // 0.6 s scene-opening motion at 30 fps
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?।॥])\\s+");

    private final ProviderGateway gateway;
    private final StorageProvider storage;
    private final MediaProcessor media;
    private final ConceptExplainerJobStore jobs;

    /** Optional animated maths / physics scenes: only present when the Manim sidecar is configured (compose profile "manim"). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aistorystudio.manim.ManimClient manim;

    public boolean manimAvailable() { return manim != null && manim.available(); }
    private final ConceptSlideRenderer renderer = new ConceptSlideRenderer();
    private final DiagramRenderer diagrams = new DiagramRenderer();
    private final ObjectMapper mapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Value("${studio.visual-renderer.base-url:}")
    private String visualRendererBaseUrl;
    /** Slow push-in over a whole scene (0.015 = 1.5 %). 0 disables motion entirely. */
    @org.springframework.beans.factory.annotation.Value("${studio.concept-explainer.subtle-zoom:0.015}")
    private double subtleZoom = 0.015;

    public ConceptExplainerService(ProviderGateway gateway, StorageProvider storage, MediaProcessor media,
                                   ConceptExplainerJobStore jobs) {
        this.gateway = gateway;
        this.storage = storage;
        this.media = media;
        this.jobs = jobs;
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String motion, String model) {
        return create(topic, instructions, language, duration, difficulty, motion, model, "GENERAL", "General", false, "narrator-male");
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String motion, String model, String track, String subject, boolean examFocus) {
        return create(topic, instructions, language, duration, difficulty, motion, model, track, subject, examFocus, "narrator-male");
    }

    public ConceptExplainerJob create(String topic, String instructions, String language, String duration,
                                      String difficulty, String motion, String model, String track, String subject, boolean examFocus, String voice) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("Enter a topic to explain.");
        String m = motion == null ? "REVEAL" : motion.trim().toUpperCase(Locale.ROOT);
        if (!m.equals("STATIC")) m = "REVEAL";
        String t = blank(track) ? "GENERAL" : track.trim().toUpperCase(Locale.ROOT);
        if (!List.of("TECHNOLOGY", "JEE", "NEET", "GENERAL", "GK", "KIDS", "KIDS_2_5", "KIDS_10_15").contains(t)) t = "GENERAL";
        String sub = blank(subject) ? (t.equals("JEE") ? "Physics" : t.equals("NEET") ? "Biology" : "General") : subject.trim();
        boolean exam = examFocus && (t.equals("JEE") || t.equals("NEET"));
        return jobs.create(topic.trim(), instructions == null ? "" : instructions.trim(),
                blank(language) ? "English" : language.trim(), blank(duration) ? "1 minute" : duration.trim(),
                blank(difficulty) ? "Complete Beginner" : difficulty.trim(), m, model == null ? "" : model.trim(), t, sub, exam,
                blank(voice) ? "narrator-male" : voice.trim());
    }

    // =================================================================== generate

    @Async("videoGenerationExecutor")
    public void generateAsync(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            job.setStatus(ConceptExplainerJob.Status.PLANNING);
            job.setStage("Writing the lesson plan");
            if (tutorial(job)) {
                planTutorial(job);
                assignVisualRoutes(job);
                if (job.isReviewScript()) {   // pre-production gate: read, edit and have the script checked BEFORE any GPU time is spent
                    enterPlanReview(job);
                    return;
                }
            } else {
                Lesson lesson = plan(job);
                job.setTitle(lesson.title());
                job.setSummary(lesson.summary());
                JsonNode scenes = lesson.scenes();
                job.getScenes().clear();
                for (int i = 0; i < scenes.size(); i++) {
                    ConceptExplainerJob.Scene s = new ConceptExplainerJob.Scene();
                    JsonNode n = scenes.get(i);
                    SceneDraft d = lesson.drafts().get(i);
                    s.setSceneNumber(i + 1);
                    s.setTemplate(d.template);
                    s.setTitle(blank(d.title) ? "Scene " + (i + 1) : d.title);
                    s.setSentences(new ArrayList<>(d.narration));          // validated + repaired narration
                    s.setNarration(String.join(" ", s.getSentences()));
                    s.setCode(text(n, "code", null));
                    s.setIllustrationPrompt(d.imagePrompt);                // derived from this scene's narration
                    s.setSectionType(d.sectionType);
                    s.setLearningObjective(d.objective);
                    s.setVisualDescription(d.visualDescription);
                    s.setVoiceDirection(d.emotion, d.pace, d.delivery);
                    s.setPlanJson(n.toString());
                    job.getScenes().add(s);
                }

            }

            assignVisualRoutes(job);
            produce(job);
        } catch (Exception e) {
            log.warn("Concept explainer {} failed: {}", id, e.getMessage(), e);
            job.setErrorMessage(rootMessage(e));
            job.setStage("Generation failed");
            job.setStatus(ConceptExplainerJob.Status.FAILED);
        }
    }


    /** Everything after the plan: pictures, voice, clips, final video (or, for kids lessons, the pictures-only storyboard). */
    private void produce(ConceptExplainerJob job) throws Exception {
        if (tutorial(job)) {
            ensureEnglishCaptions(job, blankToNull(job.getModel()));   // a scene edited at the review step needs its subtitle line again
            generatePhotos(job);   // breakdown: full-screen 3D shots; lessons: framed illustrations (anatomy, real objects)
            if (kids(job)) {
                // picture-book flow, like Create Story: stop here so every picture can be reviewed / redone first
                job.setStatus(ConceptExplainerJob.Status.GENERATING_SCENES);
                for (ConceptExplainerJob.Scene s : job.getScenes()) {
                    job.setStage("Scene " + s.getSceneNumber() + "/" + job.getScenes().size() + ": laying out the page");
                    renderSlide(job, s);
                }
                job.setStage("Pictures ready - check each scene, redo any picture, then make the video");
                job.setStatus(ConceptExplainerJob.Status.STORYBOARD);
                return;
            }
        }
        job.setStatus(ConceptExplainerJob.Status.GENERATING_SCENES);
        // All ComfyUI artwork first, then all narration: the GPU switches engine once instead of twice per
        // scene (each switch unloads one model and loads the other - see GpuMemoryManager).
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            if (needsIllustration(job, s)) {
                job.setStage("Scene " + s.getSceneNumber() + "/" + job.getScenes().size() + " - " + s.getTitle() + ": drawing the illustration");
                makeIllustration(job, s);
            }
        }
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            String base = "Scene " + s.getSceneNumber() + "/" + job.getScenes().size() + " - " + s.getTitle();
            job.setStage(base + ": drawing the slide");
            renderSlide(job, s);
            job.setStage(base + ": recording the narration");
            makeNarration(job, s);
        }
        checkVoiceClarity(job);
        if (isShort(job)) fitToBudget(job);       // all narration lengths are known now: a 60-second short must not come out at 93
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            String base = "Scene " + s.getSceneNumber() + "/" + job.getScenes().size() + " - " + s.getTitle();
            if (manimSpec(s) != null && s.getBackdropPath() != null) {
                job.setStage(base + ": animating the maths (Manim)");
                renderManimAnimation(job, s);          // after the time-fit, so the animation matches the final narration length
                if ("MANIM".equals(s.getVisualEngine())) {
                    boolean ok = s.getAnimationPath() != null;
                    s.setActualRenderer(ok ? "MANIM" : "NATIVE_DIAGRAM");
                    s.setRendererStatus(ok ? "RENDERED" : "FALLBACK");
                    s.setRendererMessage(ok ? "Manim rendered the maths/physics animation to match the narration." : "Planned MANIM; the animation failed, so the scene's final picture is shown.");
                }
            }
            job.setStage(base + ": syncing the slide to the voice");
            buildClip(job, s);
        }
        job.setStatus(ConceptExplainerJob.Status.ASSEMBLING);
        assemble(job);
        job.setStage("Ready");
        job.setStatus(ConceptExplainerJob.Status.SUCCEEDED);
    }

    @Async("videoGenerationExecutor")
    public void continueAsync(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            produce(job);
        } catch (Exception e) {
            log.warn("Concept explainer {} failed after the script review: {}", id, e.getMessage(), e);
            job.setErrorMessage(rootMessage(e));
            job.setStage("Generation failed");
            job.setStatus(ConceptExplainerJob.Status.FAILED);
        }
    }

    // =================================================================== script review gate (pre-production)

    private TutorialPrompts.Size sizeFor(ConceptExplainerJob job) {
        return isShort(job) ? TutorialPrompts.shortSize() : isBreakdown(job) ? TutorialPrompts.breakdownSize() : TutorialPrompts.size(job.getDuration());
    }

    private static String pictureText(ConceptExplainerJob.Scene s, com.fasterxml.jackson.databind.ObjectMapper m) {
        try {
            for (JsonNode e : m.readTree(s.getPlanJson() == null ? "{}" : s.getPlanJson()).path("elements"))
                if ("photo".equals(e.path("type").asText())) return e.path("text").asText(null);
        } catch (Exception ignored) { }
        return null;
    }

    /** Runs the deterministic script review over the current narration (English subtitles stand in for non-English narration). */
    com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.Report doctor(ConceptExplainerJob job) {
        TutorialPrompts.Size size = sizeFor(job);
        boolean english = TutorialPrompts.english(job.getLanguage());
        boolean anyCaptions = false;
        List<com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.SceneInput> in = new ArrayList<>();
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            List<String> sents = new ArrayList<>(s.getSentences());
            int spoken = 0;
            for (String x : sents) spoken += x.trim().isEmpty() ? 0 : x.trim().split("\\s+").length;
            if (!english && s.getCaptions().size() == sents.size() && !sents.isEmpty()) { sents = new ArrayList<>(s.getCaptions()); anyCaptions = true; }
            in.add(new com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.SceneInput(s.getSceneNumber(), s.getSectionType(), sents, pictureText(s, mapper), spoken));
        }
        int band = ageBand(job);
        double wpm = isBreakdown(job) ? 175 : band == 1 ? 135 : band == 2 ? 145 : 155;
        return com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.review(in, size.minWords(), size.maxWords(), wpm, band, english || anyCaptions);
    }

    private void enterPlanReview(ConceptExplainerJob job) {
        var r = doctor(job);
        job.setScriptReport(r);
        job.setStage("Script ready: " + r.words() + " words, about " + r.seconds() + " s"
                + (r.issues().isEmpty() ? ", nothing to flag" : ", " + r.issues().size() + " thing(s) to look at")
                + ". Edit it if you like, then continue.");
        job.setStatus(ConceptExplainerJob.Status.PLAN_REVIEW);
    }

    private ConceptExplainerJob reviewJob(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Lesson not found (it may have expired).");
        if (job.getStatus() != ConceptExplainerJob.Status.PLAN_REVIEW) throw new IllegalStateException("The script can be changed while the lesson waits at the review step.");
        return job;
    }

    /** Flips the status synchronously (so a double click cannot start two runs); the run itself is {@link #continueAsync}. */
    public synchronized void continueAfterReview(UUID id) {
        ConceptExplainerJob job = reviewJob(id);
        job.setStatus(ConceptExplainerJob.Status.GENERATING_SCENES);
        job.setStage("Drawing the pictures and recording the narration");
    }

    public synchronized void startScriptFix(UUID id) {
        ConceptExplainerJob job = reviewJob(id);
        var r = job.getScriptReport() == null ? doctor(job) : job.getScriptReport();
        if (r.issues().stream().noneMatch(i -> i.fixable())) throw new IllegalStateException("There is nothing the app can fix automatically - edit the flagged lines yourself.");
        job.setStatus(ConceptExplainerJob.Status.REGENERATING);
        job.setStage("Rewriting the flagged scenes");
    }

    @Async("videoGenerationExecutor")
    public void fixScriptAsync(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            var report = job.getScriptReport() == null ? doctor(job) : job.getScriptReport();
            java.util.Map<Integer, java.util.LinkedHashSet<String>> problems = new java.util.TreeMap<>();
            int shrink = 0;
            for (var i : report.issues()) {
                if (!i.fixable()) continue;
                if (i.scene() == 0 && i.code().equals("too-long")) { shrink = Math.max(0, report.words() - sizeFor(job).maxWords()); continue; }
                if (i.scene() > 0) problems.computeIfAbsent(i.scene(), k -> new java.util.LinkedHashSet<>()).add(i.code() + ": " + i.message());
                else for (ConceptExplainerJob.Scene s : job.getScenes()) problems.computeIfAbsent(s.getSceneNumber(), k -> new java.util.LinkedHashSet<>()).add(i.code() + ": " + i.message());
            }
            if (shrink > 0) {   // too long overall: ask the three longest scenes to give up the excess
                job.getScenes().stream().sorted((x, y) -> y.getNarration().length() - x.getNarration().length()).limit(3)
                        .forEach(s -> problems.computeIfAbsent(s.getSceneNumber(), k -> new java.util.LinkedHashSet<>()).add("too-long: shorten this scene"));
            }
            if (problems.isEmpty()) { job.setStatus(ConceptExplainerJob.Status.PLAN_REVIEW); return; }
            com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
            for (var e : problems.entrySet()) {
                ConceptExplainerJob.Scene s = job.getScenes().get(e.getKey() - 1);
                ObjectNode o = arr.addObject();
                o.put("scene", e.getKey());
                var pr = o.putArray("problems");
                e.getValue().forEach(pr::add);
                var na = o.putArray("narration");
                s.getSentences().forEach(na::add);
            }
            JsonNode out = readJson(gateway.llm().generateStructured(TutorialPrompts.doctorSystemPrompt(),
                    TutorialPrompts.doctorUserPrompt(arr.toString(), shrink), blankToNull(job.getModel())));
            int changed = 0, skipped = 0;
            for (JsonNode sc : out.path("scenes")) {
                int n = sc.path("scene").asInt(0);
                if (n < 1 || n > job.getScenes().size()) continue;
                ConceptExplainerJob.Scene s = job.getScenes().get(n - 1);
                List<String> fixed = rawSentences(sc.path("narration"));
                if (fixed.size() != s.getSentences().size()) { skipped++; continue; }   // sentence count must not change: visuals are timed to it
                s.setSentences(new ArrayList<>(fixed));
                s.setNarration(String.join(" ", fixed));
                s.setCaptions(new ArrayList<>());
                s.setPauseAfter(new ArrayList<>(com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.silenceBeats(fixed, kids(job))));
                changed++;
            }
            ensureEnglishCaptions(job, blankToNull(job.getModel()));
            job.setScriptReport(doctor(job));
            job.getWarnings().add("Script doctor rewrote " + changed + " scene(s)" + (skipped > 0 ? " (" + skipped + " answer(s) were ignored because the sentence count changed)" : "") + ".");
        } catch (Exception e) {
            log.warn("Script fix failed for {}: {}", id, e.getMessage());
            job.getWarnings().add("The automatic rewrite did not work (" + rootMessage(e) + ") - edit the flagged lines yourself.");
        }
        enterPlanReviewKeepReport(job);
    }

    private void enterPlanReviewKeepReport(ConceptExplainerJob job) {
        var r = job.getScriptReport() == null ? doctor(job) : job.getScriptReport();
        job.setScriptReport(r);
        job.setStage("Script updated: " + r.words() + " words, about " + r.seconds() + " s"
                + (r.issues().isEmpty() ? ", nothing to flag" : ", " + r.issues().size() + " thing(s) left to look at") + ".");
        job.setStatus(ConceptExplainerJob.Status.PLAN_REVIEW);
    }

    // =================================================================== concepts first (the proposal step)

    /** Three differentiated concepts for the topic, before anything is made. Nothing is stored; the creator picks one (or none). */
    public List<java.util.Map<String, String>> proposeConcepts(String topic, String language, String duration, String difficulty,
                                                                String audience, String outcome, String tone, String track, String model) throws Exception {
        if (blank(topic)) throw new IllegalArgumentException("Type the topic first.");
        String notes = switch (track == null ? "" : track) {
            case "KIDS_2_5" -> "The audience is children aged 2-5: simple, concrete, playful, one idea.";
            case "KIDS" -> "The audience is children aged 5-10: a small mystery, everyday examples, curiosity.";
            case "KIDS_10_15" -> "The audience is teenagers aged 10-15: respect their intelligence, a real puzzle, no talking down.";
            default -> "";
        };
        JsonNode out = readJson(gateway.llm().generateStructured(TutorialPrompts.conceptsSystemPrompt(),
                TutorialPrompts.conceptsUserPrompt(topic.trim(), blank(language) ? "English" : language, blank(duration) ? "about 1 minute" : duration,
                        blank(difficulty) ? "Beginner" : difficulty, audience, outcome, tone, notes), blankToNull(model)));
        List<java.util.Map<String, String>> list = new ArrayList<>();
        for (JsonNode c : out.path("concepts")) {
            if (list.size() >= 3) break;
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (String k : List.of("title", "hookType", "hook", "angle", "approach", "visualIdea", "whyItWorks")) {
                String v = text(c, k, "");
                m.put(k, v.length() > 300 ? v.substring(0, 300) : v);
            }
            if (!m.get("hook").isBlank() || !m.get("angle").isBlank()) list.add(m);
        }
        if (list.isEmpty()) throw new IllegalStateException("The model did not return usable concepts - try again, or pick a larger model.");
        return list;
    }

    // =================================================================== storyboard (kids picture-book flow)

    private ConceptExplainerJob storyboardJob(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Lesson not found (it may have expired).");
        if (job.getStatus() != ConceptExplainerJob.Status.STORYBOARD)
            throw new IllegalStateException("Pictures can be changed while the lesson is waiting at the storyboard step.");
        return job;
    }

    /** Edit what a scene says before the video is made (one sentence per line). */
    public synchronized void updateNarration(UUID id, int sceneNumber, List<String> sentences) throws IOException {
        ConceptExplainerJob early = jobs.get(id);
        if (early != null && early.getStatus() == ConceptExplainerJob.Status.PLAN_REVIEW) {   // before any picture or voice exists: just edit the text
            if (sceneNumber < 1 || sceneNumber > early.getScenes().size()) throw new IllegalArgumentException("Invalid scene number.");
            List<String> clean = new ArrayList<>();
            for (String x : sentences == null ? List.<String>of() : sentences) if (x != null && !x.isBlank()) clean.add(x.trim());
            if (clean.isEmpty()) throw new IllegalArgumentException("A scene needs at least one sentence.");
            ConceptExplainerJob.Scene es = early.getScenes().get(sceneNumber - 1);
            es.setSentences(clean);
            es.setNarration(String.join(" ", clean));
            es.setCaptions(new ArrayList<>());
            es.setPauseAfter(new ArrayList<>(com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.silenceBeats(clean, kids(early))));
            early.setScriptReport(doctor(early));
            return;
        }
        ConceptExplainerJob job = storyboardJob(id);
        if (sceneNumber < 1 || sceneNumber > job.getScenes().size()) throw new IllegalArgumentException("Invalid scene number.");
        List<String> clean = new ArrayList<>();
        for (String x : sentences == null ? List.<String>of() : sentences) if (x != null && !x.isBlank()) clean.add(x.trim());
        if (clean.isEmpty()) throw new IllegalArgumentException("A scene needs at least one sentence.");
        ConceptExplainerJob.Scene s = job.getScenes().get(sceneNumber - 1);
        s.setSentences(clean);
        s.setNarration(String.join(" ", clean));
        s.setCaptions(new ArrayList<>());
        renderSlide(job, s);   // overlays are timed per sentence
    }

    /** Synchronous part of "redo this picture": flips the status so a double click cannot start two. */
    public synchronized void redoPicture(UUID id, int sceneNumber, String newDescription) throws IOException {
        ConceptExplainerJob job = storyboardJob(id);
        if (sceneNumber < 1 || sceneNumber > job.getScenes().size()) throw new IllegalArgumentException("Invalid scene number.");
        ConceptExplainerJob.Scene s = job.getScenes().get(sceneNumber - 1);
        ObjectNode plan = (ObjectNode) mapper.readTree(s.getPlanJson());
        for (JsonNode e : plan.withArray("elements")) {
            if (!"photo".equals(e.path("type").asText())) continue;
            ((ObjectNode) e).remove("content");
            if (newDescription != null && !newDescription.isBlank()) ((ObjectNode) e).put("text", newDescription.trim());
        }
        s.setPlanJson(plan.toString());
        job.setStatus(ConceptExplainerJob.Status.REGENERATING);
        job.setStage("Drawing a new picture for scene " + sceneNumber);
    }

    @Async("videoGenerationExecutor")
    public void redoPictureAsync(UUID id, int sceneNumber) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            generatePhotos(job);   // only the scene whose picture was cleared is drawn (same cast reference)
            renderSlide(job, job.getScenes().get(sceneNumber - 1));
            job.setStage("Pictures ready - check each scene, redo any picture, then make the video");
        } catch (Exception e) {
            job.getWarnings().add("Scene " + sceneNumber + ": the new picture failed (" + rootMessage(e) + ").");
        }
        job.setStatus(ConceptExplainerJob.Status.STORYBOARD);
    }

    /** Synchronous part of "make the video": animated = Wan animates every picture; otherwise slow camera moves. */
    public synchronized void startVideo(UUID id, boolean animated) {
        ConceptExplainerJob job = storyboardJob(id);
        job.setAnimatedCartoon(animated);
        job.setStatus(ConceptExplainerJob.Status.GENERATING_SCENES);
        job.setStage(animated ? "Recording the narration, then animating every picture" : "Recording the narration");
    }

    @Async("videoGenerationExecutor")
    public void makeVideoAsync(UUID id) {
        ConceptExplainerJob job = jobs.get(id);
        if (job == null) return;
        try {
            int n = job.getScenes().size();
            for (ConceptExplainerJob.Scene s : job.getScenes()) {   // all voices first (one GPU switch), they set each scene's length
                job.setStage("Scene " + s.getSceneNumber() + "/" + n + ": recording the narration");
                makeNarration(job, s);
            }
            if (job.isAnimatedCartoon()) {
                for (ConceptExplainerJob.Scene s : job.getScenes()) {   // then all Wan animations
                    job.setStage("Scene " + s.getSceneNumber() + "/" + n + ": animating the picture (Wan)");
                    try {
                        animateScene(job, s);
                    } catch (Exception e) {
                        s.setAnimationPath(null);
                        job.getWarnings().add("Scene " + s.getSceneNumber() + ": animation failed (" + rootMessage(e) + ") - used a camera move instead.");
                    }
                }
            }
            for (ConceptExplainerJob.Scene s : job.getScenes()) {
                job.setStage("Scene " + s.getSceneNumber() + "/" + n + ": putting picture, words and voice together");
                buildClip(job, s);
            }
            job.setStatus(ConceptExplainerJob.Status.ASSEMBLING);
            assemble(job);
            job.setStage("Ready");
            job.setStatus(ConceptExplainerJob.Status.SUCCEEDED);
        } catch (Exception e) {
            log.warn("Kids video {} failed: {}", id, e.getMessage(), e);
            job.setErrorMessage(rootMessage(e));
            job.setStage("Video failed - the pictures are kept, press Make video again");
            job.setStatus(ConceptExplainerJob.Status.STORYBOARD);
        }
    }

    /**
     * Animates one scene's picture with Wan image-to-video (the Story flow's engine): gentle character life - blinks,
     * smiles, small gestures, drifting light - with the scene unchanged. A narration longer than one Wan clip is covered by
     * chaining: the next clip starts from the previous clip's last frame (max 3); any remainder holds the last frame.
     */
    private void animateScene(ConceptExplainerJob job, ConceptExplainerJob.Scene s) throws Exception {
        String picture = s.getBackdropPath();
        if (picture == null || !Files.isRegularFile(Path.of(picture))) return;
        double need = Math.max(2, s.getDurationSeconds());
        double seg = 5.0;
        int clips = (int) Math.min(3, Math.ceil(need / seg));
        String what = "";
        for (JsonNode e : mapper.readTree(s.getPlanJson()).path("elements")) if ("photo".equals(e.path("type").asText())) what = e.path("text").asText("");
        String prompt = what + ". Gentle, natural character animation: the characters blink, smile, breathe and make small expressive "
                + "gestures (pointing, nodding, a little wave); hair and clothes move slightly; soft light and sparkles drift; very slow "
                + "camera drift. The scene, characters and colours stay exactly the same. High-end 3D animated feature film, smooth motion.";
        String neg = "scene change, new characters, distorted face, extra limbs, flicker, text, subtitles, fast motion, camera shake";
        int w = vertical(job) ? 480 : 832, h = vertical(job) ? 832 : 480;
        Path dirp = storage.resolve(dir(job) + "/anim");
        Files.createDirectories(dirp);
        List<Path> parts = new ArrayList<>();
        String start = picture;
        for (int i = 0; i < clips; i++) {
            var r = gateway.generateVideo(new com.aistorystudio.provider.VideoGenerationProvider.VideoGenerationRequest(
                    start, prompt, neg, seg, w, h, null, null, null, 0));
            Path part = dirp.resolve("scene-" + num(s) + "-part" + i + "." + (r.fileExtension() == null ? "mp4" : r.fileExtension().replace(".", "")));
            Files.write(part, r.videoBytes());
            parts.add(part);
            if (i < clips - 1) {   // continue from where this clip ends
                Path last = dirp.resolve("scene-" + num(s) + "-part" + i + "-last.png");
                ffmpeg(new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-sseof", "-0.1", "-i", part.toString(), "-frames:v", "1", "-update", "1", last.toString())));
                start = last.toString();
            }
        }
        Path out = dirp.resolve("scene-" + num(s) + "-anim.mp4");
        if (parts.size() == 1) Files.copy(parts.get(0), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        else {
            List<String> a = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
            StringBuilder f = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) { a.addAll(List.of("-i", parts.get(i).toString())); f.append('[').append(i).append(":v]fps=24,setsar=1,format=yuv420p[p").append(i).append("];"); }
            for (int i = 0; i < parts.size(); i++) f.append("[p").append(i).append(']');
            f.append("concat=n=").append(parts.size()).append(":v=1:a=0[v]");
            a.addAll(List.of("-filter_complex", f.toString(), "-map", "[v]", "-an", "-c:v", "libx264", "-crf", "16", "-pix_fmt", "yuv420p", out.toString()));
            ffmpeg(a);
        }
        s.setAnimationPath(out.toString());
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
                if (needsIllustration(job, s)) makeIllustration(job, s);
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
        ConceptExplainerJob copy = create(old.getTopic(), old.getInstructions(), old.getLanguage(), old.getDuration(), old.getDifficulty(),
                old.getMotion(), old.getModel(), old.getTrack(), old.getSubject(), old.isExamFocus(), old.getVoice());
        copy.setStyle(old.getStyle());
        copy.setFormat(old.getFormat());
        copy.setTeachingStyle(old.getTeachingStyle());
        copy.setDestination(old.getDestination());
        return copy;
    }

    // =================================================================== plan

    // =================================================================== plan (adaptive teaching framework)

    /** The finished lesson: slide JSON per scene + the validated/repaired drafts (narration, voice, alignment). */
    private record Lesson(String title, String summary, JsonNode scenes, List<SceneDraft> drafts, int words) { }

    private TeachingPlanner.Request planRequest(ConceptExplainerJob job) {
        boolean deep = job.isDeepDive();
        return new TeachingPlanner.Request(job.getTopic(), job.getLanguage(), job.getDifficulty(), job.getTrack(), job.getSubject(),
                job.isExamFocus(), job.getInstructions(), trackInstruction(job), deep,
                TeachingStyle.parse(job.getTeachingStyle()), Destination.parse(job.getDestination()),
                deep ? 16 : 8, deep ? 20 : 10, deep ? 430 : 145, deep ? 520 : 180);
    }

    /**
     * 1) PLAN: outline with a learning objective, teaching section and slide template per scene (audience, hook, analogy,
     *    real-world example and humour chosen for THIS topic).  2) WRITE: narration -> slide -> visual description ->
     *    image prompt -> voice direction, each derived from the same scene.  3) VALIDATE: deterministic repairs (opening line,
     *    stage directions, voice values, closing) + one targeted LLM repair for what is still wrong.  4) length correction.
     */
    private Lesson plan(ConceptExplainerJob job) {
        TeachingPlanner.Request req = planRequest(job);
        String model = blankToNull(job.getModel());

        job.setStage("Planning the lesson (audience, analogy, objectives per scene)");
        JsonNode outline = readJson(gateway.llm().generateStructured(TeachingPlanner.planSystemPrompt(), TeachingPlanner.planUserPrompt(req), model));
        if (!outline.path("scenes").isArray() || outline.path("scenes").size() < 3) {
            throw new IllegalStateException("The lesson planner returned no usable outline (needs at least 3 scenes).");
        }
        List<String> outlineSections = new ArrayList<>(), outlineObjectives = new ArrayList<>();
        for (JsonNode o : outline.path("scenes")) {
            SectionType st = SectionType.parse(text(o, "sectionType", null));
            outlineSections.add(st == null ? text(o, "sectionType", "CONCEPT") : st.name());
            outlineObjectives.add(text(o, "learningObjective", text(o, "keyIdea", null)));
        }

        job.setStage("Writing the narration, slides and voice directions");
        JsonNode written = readJson(gateway.llm().generateStructured(TeachingPlanner.writeSystemPrompt(), TeachingPlanner.writeUserPrompt(req, outline.toString()), model));
        validate(written);
        LessonValidator.Context ctx = new LessonValidator.Context(job.getTopic(), job.getLanguage(), req.destination(), req.kind(), req.style(),
                outlineSections, outlineObjectives);
        LessonValidator validator = new LessonValidator();
        List<SceneDraft> drafts = drafts(written);
        LessonValidator.Report report = validator.check(drafts, ctx);

        if (report.hasErrors()) {
            job.setStage("Fixing " + report.errors().size() + " problem(s) in the lesson");
            try {
                JsonNode fixed = readJson(gateway.llm().generateStructured(TeachingPlanner.writeSystemPrompt(),
                        TeachingPlanner.repairUserPrompt(req, written.toString(), report.errorMessages()), model));
                validate(fixed);
                List<SceneDraft> fixedDrafts = drafts(fixed);
                LessonValidator.Report fixedReport = validator.check(fixedDrafts, ctx);
                if (fixedReport.errors().size() < report.errors().size()) { written = fixed; drafts = fixedDrafts; report = fixedReport; }
            } catch (Exception e) {
                job.getWarnings().add("Lesson repair failed: " + e.getMessage());
            }
        }
        // Length correction (one round): too short / too long for the chosen mode.
        int words = words(drafts);
        if (words < req.minWords() * 0.75 || words > req.maxWords() * 1.3) {
            job.setStage("Adjusting the lesson length (" + words + " words, target " + req.minWords() + "-" + req.maxWords() + ")");
            try {
                JsonNode second = readJson(gateway.llm().generateStructured(TeachingPlanner.writeSystemPrompt(),
                        TeachingPlanner.lengthFixPrompt(req, written.toString(), words), model));
                validate(second);
                List<SceneDraft> secondDrafts = drafts(second);
                LessonValidator.Report secondReport = validator.check(secondDrafts, ctx);
                int w2 = words(secondDrafts), mid = (req.minWords() + req.maxWords()) / 2;
                if (Math.abs(w2 - mid) < Math.abs(words - mid) && secondReport.errors().size() <= report.errors().size()) {
                    written = second; drafts = secondDrafts; report = secondReport; words = w2;
                }
            } catch (Exception e) {
                job.getWarnings().add("Length correction failed: " + e.getMessage());
            }
        }
        for (SceneDraft d : drafts) {
            if (d.narration.isEmpty()) throw new IllegalStateException("Scene " + d.number + " has no narration after validation - try again or choose another model.");
        }
        job.setWordCount(words);
        if (words < req.minWords() * 0.75 || words > req.maxWords() * 1.3) {
            job.getWarnings().add("Lesson has " + words + " spoken words (target " + req.minWords() + "-" + req.maxWords() + "), so it will run "
                    + (words < req.minWords() ? "short" : "long") + ".");
        }
        int shown = 0;
        for (LessonValidator.Issue i : report.issues()) {
            if (i.level() == LessonValidator.Level.FIXED) continue;
            if (shown++ < 6) job.getWarnings().add("Alignment: " + i);
        }
        int fixes = report.count(LessonValidator.Level.FIXED);
        if (fixes > 0) job.getWarnings().add("Auto-corrected " + fixes + " small thing(s) (opening line, stage directions, voice values...).");
        return new Lesson(text(written, "title", text(outline, "title", job.getTopic())),
                text(written, "summary", text(outline, "summary", "A visual lesson on " + job.getTopic())), written.path("scenes"), drafts, words);
    }

    private List<SceneDraft> drafts(JsonNode lesson) {
        List<SceneDraft> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : lesson.path("scenes")) out.add(draftOf(n, i++));
        return out;
    }

    /** JSON scene -> the plain draft the validator understands (what is spoken, what is on the slide, how it sounds). */
    private SceneDraft draftOf(JsonNode n, int index) {
        SceneDraft d = new SceneDraft();
        d.number = index + 1;
        d.sectionType = text(n, "sectionType", null);
        d.objective = text(n, "learningObjective", text(n, "objective", null));
        d.template = template(n);
        d.title = text(n, "title", null);
        d.narration = rawSentences(n.path("narration"));
        d.visualDescription = text(n, "visualDescription", null);
        d.imagePrompt = text(n, "imagePrompt", text(n, "illustration", null));
        JsonNode vd = n.path("voiceDirection");
        d.emotion = text(vd, "emotion", null);
        d.pace = text(vd, "pace", null);
        d.delivery = text(vd, "delivery", null);
        term(d, n, "text"); term(d, n, "caption"); term(d, n, "statement"); term(d, n, "formula"); term(d, n, "formulaResult");
        if (!blank(text(n, "text", null))) d.counts.put("text", 1);
        if (!blank(text(n, "statement", null))) d.counts.put("statement", 1);
        if (!blank(text(n, "code", null))) d.counts.put("code", 1);
        JsonNode box = n.path("box");
        if (!blank(text(box, "label", null))) { d.counts.put("box", 1); term(d, box, "label"); term(d, box, "value"); }
        JsonNode after = n.path("after");
        if (after.isObject()) { term(d, after, "label"); term(d, after, "value"); }
        int callouts = 0;
        for (JsonNode c : n.path("callouts")) { callouts++; term(d, c, "label"); term(d, c, "detail"); }
        d.counts.put("callouts", callouts);
        int parts = 0;
        for (JsonNode c : n.path("parts")) { parts++; term(d, c, "label"); term(d, c, "detail"); term(d, c, "token"); }
        d.counts.put("parts", parts);
        int cols = 0;
        for (JsonNode c : n.path("columns")) { cols++; if (!blank(c.asText(""))) d.slideTerms.add(c.asText("")); }
        d.counts.put("columns", cols);
        int rows = 0;
        for (JsonNode r : n.path("rows")) {
            rows++;
            if (r.isArray()) for (JsonNode c : r) { if (!blank(c.asText(""))) d.slideTerms.add(c.asText("")); }
        }
        d.counts.put("rows", rows);
        int items = 0, left = 0, right = 0;
        for (JsonNode c : n.path("items")) {
            items++;
            term(d, c, "label"); term(d, c, "text");
            if (d.template.equals("flow") && !blank(text(c, "label", null))) d.flowLabels.add(text(c, "label", ""));
            boolean ok = !c.has("ok") || c.path("ok").asBoolean(true);
            if (ok) left++; else right++;
        }
        d.counts.put("items", items);
        d.counts.put("nodes", d.flowLabels.size());
        d.counts.put("left", left);
        d.counts.put("right", right);
        int maps = 0;
        for (JsonNode c : n.path("mappings")) { maps++; term(d, c, "left"); term(d, c, "right"); }
        d.counts.put("mappings", maps);
        return d;
    }

    private static void term(SceneDraft d, JsonNode n, String field) {
        String v = text(n, field, null);
        if (!blank(v)) d.slideTerms.add(v);
    }

    private int words(List<SceneDraft> drafts) {
        int w = 0;
        for (SceneDraft d : drafts) for (String s : d.narration) w += s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
        return w;
    }

    /** Which visual devices suit a subject - appended to every track instruction so diagrams match the discipline. */
    static String subjectVisuals(String subject) {
        String x = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (x.contains("physics")) return " VISUALS FOR PHYSICS: forces (free-body), wave, circuit, plot (graphs with real values), formula with legend, "
                + "steps for derivations, compare (vector vs scalar...), meter, quiz for a numerical checkpoint.";
        if (x.contains("chem")) return " VISUALS FOR CHEMISTRY: atom (shells), molecule (ball-and-stick), reaction (balanced, subscripts), element tiles, "
                + "layers / tree for classification, compare (ionic vs covalent...), cycle, quiz.";
        if (x.contains("bio") || x.contains("science") && x.contains("life")) return " VISUALS FOR BIOLOGY: a framed photo of the structure + pins to label "
                + "its parts, cycle (life cycles, Krebs...), steps (processes), layers / tree (classification, levels of organisation), compare, checklist "
                + "(symptoms, functions), quiz. Use body / cell / plant icons, never tech icons.";
        if (x.contains("math")) return " VISUALS FOR MATHS: numberline, triangle, fraction, plot, formula with legend, steps for each solution step, "
                + "matrix / compare for methods, quiz with a worked answer.";
        if (x.contains("general") || x.contains("social") || x.contains("history") || x.contains("geograph") || x.contains("knowledge"))
            return " VISUALS FOR GENERAL KNOWLEDGE: timeline, factcard, compare, tree, pie (real figures only), a framed photo + pins (places, monuments), "
                    + "quiz. Facts must be accurate and well established; say 'about' for rounded numbers.";
        return " VISUALS: choose devices that fit the subject (see the catalog), not tech diagrams by default.";
    }

    private String trackInstruction(ConceptExplainerJob job) {
        return trackInstructionBase(job) + narrationTrackInstruction(job) + universalVisualQualityInstruction(job)
                + subjectVisuals(job.getSubject()) + briefText(job) + "\n" + com.aistorystudio.conceptexplainer.tutorial.IconLibrary.catalog(iconProfile(job));
    }

    /** Narration tracks are distinct teaching approaches, not merely different TTS voices. */
    private String narrationTrackInstruction(ConceptExplainerJob job) {
        TeachingStyle t = TeachingStyle.parse(job.getTeachingStyle());
        String voiceDirection = switch (t) {
            case HUMOROUS_EXPLAINER -> "VOICE PERFORMANCE: conversational Indian tutor, playful but clear; vary pace naturally, deliver a short comic beat then pause before the explanation. Prefer playful/energetic emotion and comedic or conversational delivery. At most one small joke per scene; never joke at a learner's expense.";
            case LOGIC_FIRST_ANALYST -> "VOICE PERFORMANCE: composed, reasoning-led analyst; steady pace, crisp articulation, slower emphasis for definitions, equations and conclusions. Prefer calm/serious emotion and explanatory/emphatic delivery. No comic asides or exaggerated excitement.";
            case STORY_DRIVEN_MENTOR -> "VOICE PERFORMANCE: warm storyteller-mentor; curious opening, varied pace, brief suspense before reveals, warm delivery for the lesson learned. Prefer curious/warm/dramatic emotion and storytelling delivery. Do not overact or let suspense delay the explanation.";
            case PROFESSIONAL_INSTRUCTOR -> "VOICE PERFORMANCE: confident professional instructor; even pace, clean articulation, deliberate emphasis on key terms, minimal emotion and no jokes.";
            case SIMPLE_BEGINNER -> "VOICE PERFORMANCE: patient and encouraging; short phrases, slightly slower pace for new terms, warm reassurance without sounding childish.";
            case STORYTELLING_TEACHER -> "VOICE PERFORMANCE: warm and curious storyteller; natural narrative rhythm, gentle pauses, clear explanation after each story beat.";
            default -> "VOICE PERFORMANCE: engaging tutor; conversational Indian-English-friendly cadence, natural emphasis, light energy and occasional topic-related humour without shouting.";
        };
        return "\nNARRATION TRACK (must affect the script, scene structure and per-scene voiceDirection): " + t.label() + ". " + t.promptFragment() + "\n" + voiceDirection
                + "\nDo not use the same opening formula, analogy, punchline or CTA for every topic. Match pacing and vocabulary to the subject, learner age, selected language and duration.";
    }

    /** Shared visual brief appended to every lesson/story/reel/breakdown planning request. */
    private String universalVisualQualityInstruction(ConceptExplainerJob job) {
        String ratio = vertical(job) ? "vertical 9:16" : "widescreen 16:9";
        return "\nUNIVERSAL VISUAL QUALITY BAR (applies to every subject and format): "
                + "Design for a premium " + ratio + " video and phone-size readability. Each scene must have one clear visual purpose that directly proves, demonstrates, or emotionally supports its narration. "
                + "Use subject-appropriate visual language: real objects and environments for everyday/history topics; accurate labelled diagrams, graphs and worked steps for science/math; readable code, UI and data-flow for software; expressive character action and varied shot sizes for stories/kids; evidence-led charts for business. "
                + "Vary the visual device and composition across adjacent scenes (wide establishing shot, close-up, step flow, comparison, cutaway, timeline, graph, code/UI, physical demonstration, character reaction) while preserving a coherent art direction and recurring-character identity. "
                + "Use meaningful foreground/midground/background depth, directional lighting, material detail, perspective, clear focal hierarchy, and deliberate negative space for captions/labels. Motion must communicate a state change or cause-and-effect, not be decorative. "
                + "Never use a generic icon collage when a concrete demonstration would teach more. Never invent labels, data, formulas, UI text, or scientific structure. Generated artwork must contain no text/logos/watermarks; render typography separately and keep it short, high-contrast and inside safe margins. "
                + "Avoid repeating the same composition, static full-screen slide, or stock visual in consecutive scenes. Include a real-world shot or visual analogy where it materially improves understanding, but do not add random cinematic filler.";
    }

    /** Which icon names the planner is shown: what a lesson about this needs, not all 800. */
    static String iconProfile(ConceptExplainerJob job) {
        String t = job.getTrack() == null ? "" : job.getTrack();
        String subj = job.getSubject() == null ? "" : job.getSubject().toLowerCase(Locale.ROOT);
        if (t.startsWith("KIDS")) return "KIDS";
        if (t.equals("GK")) return "GK";
        if (subj.contains("math")) return "MATHS";
        if (subj.contains("physic") || subj.contains("chem") || subj.contains("bio") || subj.contains("science") || subj.contains("health") || subj.contains("medical")
                || t.equals("NEET") || t.equals("JEE")) return "SCIENCE";
        if (subj.contains("business") || subj.contains("finance") || subj.contains("econom")) return "BUSINESS";
        if (t.equals("TECHNOLOGY")) return "TECH";
        return "GENERAL";
    }

    /** The creator's brief, the chosen concept and the creative dials, as instructions for the planner and the scene writer. */
    private String briefText(ConceptExplainerJob job) {
        StringBuilder b = new StringBuilder();
        if (job.getAudience() != null || job.getOutcome() != null || job.getTone() != null) {
            b.append(" BRIEF FROM THE CREATOR (honour it in word choice, examples and depth):");
            if (job.getAudience() != null) b.append(" audience: ").append(job.getAudience()).append(';');
            if (job.getOutcome() != null) b.append(" after watching, the viewer should ").append(job.getOutcome()).append(';');
            if (job.getTone() != null) b.append(" tone: ").append(job.getTone()).append('.');
        }
        if (job.getConcept() != null) {
            try {
                JsonNode c = mapper.readTree(job.getConcept());
                b.append(" CHOSEN CONCEPT - build the whole video on exactly this: title \"").append(text(c, "title", "")).append("\"; hook type ")
                        .append(text(c, "hookType", "")).append("; opening line idea: \"").append(text(c, "hook", "")).append("\"; angle: ")
                        .append(text(c, "angle", "")).append("; approach: ").append(text(c, "approach", "")).append("; visuals: ").append(text(c, "visualIdea", "")).append('.');
            } catch (Exception e) {
                b.append(" CHOSEN CONCEPT: ").append(job.getConcept());
            }
        }
        String v = job.getVisualVariance(), m = job.getMotionIntensity(), d = job.getDensity();
        if ("LOW".equals(v)) b.append(" Visual variety LOW: keep one tight visual system and reuse the same device family.");
        if ("HIGH".equals(v)) b.append(" Visual variety HIGH: every beat may use a different visual device.");
        if ("LOW".equals(m)) b.append(" Motion LOW: calm holds, soft changes, few new elements.");
        if ("HIGH".equals(m)) b.append(" Motion HIGH: quick changes, a new element almost every sentence.");
        if ("LOW".equals(d)) b.append(" Information density LOW: one idea per frame, at most 3 elements.");
        if ("HIGH".equals(d)) b.append(" Information density HIGH: layered detail, 5-8 elements, call-outs.");
        return b.toString();
    }

    private String trackInstructionBase(ConceptExplainerJob job) {
        String t = job.getTrack();
        int band = ageBand(job);
        String story = band == 3
                ? " NARRATION AS A STORY: follow the named teen maker through one real project from problem to working result; their"
                  + " thoughts, a failed first try, the fix. Short spoken sentences, a hook at the start of every scene."
                : " NARRATION AS A STORYBOOK, NOT A LECTURE: a storyteller tells a little adventure about the named child hero and the"
                  + " robot buddy (give them names in the outline, e.g. Aarav and Bolt, Mira and Pixel). Open like a story ('One sunny"
                  + " morning...'), let the characters talk in short quoted lines ('\"Look!\" said Mira.'), use sound words (whoosh, beep,"
                  + " pop), wonder and feelings ('Mira's eyes went wide.'). The idea is discovered INSIDE the story, never listed. Every"
                  + " scene also gives a \"picture\" field: one rich picture-book shot - who, doing what, where, the expression, the"
                  + " camera (close-up / wide), the light.";
        String scenes = story + " ILLUSTRATED SCENES (required): EVERY scene starts with ONE full-screen 'photo' element (no x/y/w/h, style \"full\") "
                + "whose text describes that scene's picture: the same characters in the same world doing what the narration says. Keep the top "
                + "third of the picture calm and empty (sky, wall, soft light) for the title, characters in the lower two thirds. On top of it put "
                + "at most 2 overlays: 'cloudtitle' (text = title, title = 3 short words joined by ' · ') on the first scene and at each new part, "
                + "'iconrow' (lines \"Label|icon or 3-letter text|colour\", 3-4 items) when listing ideas, 'speech' for a line said to the child, and "
                + "the age's interaction device below. Place overlays at y 10-34 or y 70-90, never over the middle of the picture. Include in the "
                + "outline JSON a \"characters\" field: one exact visual description of the recurring characters (age, hair, clothes, colours, the "
                + "robot buddy's look) that is reused word for word for every picture.";
        if (band == 1) {
            // Ages 2-5 (preoperational): slow, realistic pacing - 9 min of a fast fantastical cartoon impaired 4-year-olds' executive
            // function, a slow realistic one did not (Lillard & Peterson 2011, Pediatrics); at 2.5-4.5 a character who talks WITH the
            // child (parasocial interaction) improves learning from video; familiar characters teach better (Calvert); repetition
            // builds mastery (Blue's Clues). AAP: high-quality programmes, watched together with a caregiver.
            return "LITTLE LEARNERS MODE (ages 2-5): a toddler hero and a round, friendly white robot buddy in a cozy playroom with toys, "
                    + "soft warm morning light. ONE thing per scene, shown big, said slowly. Sentences of 3-7 very simple words. Say the key "
                    + "word three times ('Ball! A big ball! Ball!'). Talk to the child and wait: ask 'Can you find the red one?' as its own "
                    + "sentence ending with '?', then answer gently. Name colours, shapes, sounds, numbers 1-5, big and small, same and "
                    + "different. Calm, warm, happy - no fast changes, no scary or loud moments, no sarcasm, no reading needed. Interaction "
                    + "devices: 'choose' (2 big pictures, right one lights up on the answer sentence via highlightAt), 'count' (1-5 things), "
                    + "'sizes', 'bigpic'. A gentle song-like repeated line at the end. Topics must be what a toddler can see and touch."
                    + scenes;
        }
        if (band == 3) {
            // Ages 10-15 (formal operations emerging): teens are more sensitive to being respected and given status, and messages framed
            // as autonomy work where 'you should' messages fail (Yeager, Dahl & Dweck 2018; Bryan, Yeager et al. 2016/2019); curiosity
            // activates reward circuitry and improves memory, also for nearby material (Gruber, Gelman & Ranganath 2014, Neuron).
            return "TECH TEENS MODE (ages 10-15): a teen maker in a modern home studio with laptops and soft holographic screens, cool "
                    + "blue and purple light - no baby robot, nothing childish. Open with a curiosity gap: a real puzzle about something they "
                    + "use (games, phones, YouTube, music, sport) whose answer arrives later. Talk to them as capable people: 'you might "
                    + "try', never 'you should'. Show how it really works, including the trade-offs; let them reason before the answer. Use "
                    + "'level' headers with an XP bar (style = progress %), 'challenge' cards (answer revealed by highlightAt on the answer "
                    + "sentence), compare (myth vs fact), steps for an IDEA-PLAN-BUILD-TEST-IMPROVE flow, and a final 'build this yourself' idea. "
                    + "Light humour, no talking down, correct technical words explained once."
                    + scenes;
        }
        if (band == 2) {
            // Ages 5-10 (late preoperational -> concrete operations): logic works best on concrete things and pictures of them
            // (Piaget); a familiar guide and pseudo-interaction with pauses (Blue's Clues, Disney Research); repetition (mastery);
            // gentle story + humour (Sesame Street); segmenting / signalling / weeding (Mayer; Ibrahim et al. 2012).
            return "YOUNG EXPLORERS MODE (ages 5-10): a curious child hero and a friendly white robot buddy in a cozy evening study with a "
                    + "night sky, glowing light bulb ideas, rocket and doodles. Make the lesson a small mystery with three clues the child helps "
                    + "solve. Talk to the child: 'you', 'let's', 'can you guess?' - ask, pause, answer. Explain with things they can picture: "
                    + "school, lunch boxes, toys, the playground, posting a letter, pizza. Sentences of 4-10 easy words; every new word "
                    + "explained like to a 7-year-old; the key idea repeated three ways. Interaction devices: analogy, steps, quiz (2 easy "
                    + "questions with picture-like options), factcard ('Did you know?'), and a 'badge' to finish. Warm and funny, never scary."
                    + scenes;
        }
        if ("GK".equals(t)) {
            return "GENERAL KNOWLEDGE MODE: Tell it like a short documentary - a surprising hook, the who / when / where / why, the turning "
                    + "point, why it matters today, and one memorable fact. Only well-established facts; give years and names exactly or not "
                    + "at all. End with a 1-question quiz.";
        }

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

    /** Spoken sentences exactly as written (no placeholder), so validation can see a missing narration. */
    private List<String> rawSentences(JsonNode narration) {
        List<String> out = new ArrayList<>();
        if (narration.isArray()) {
            for (JsonNode n : narration) { String c = NarrationScrub.clean(clean(n.asText(""))); if (!c.isBlank()) out.add(c); }
        } else if (!narration.asText("").isBlank()) {
            for (String s : SENTENCE.split(narration.asText().trim())) { String c = NarrationScrub.clean(clean(s)); if (!c.isBlank()) out.add(c); }
        }
        return out;
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
            for (JsonNode n : narration) { String c = NarrationScrub.clean(clean(n.asText(""))); if (!c.isBlank()) out.add(c); }
        } else if (!narration.asText("").isBlank()) {
            for (String s : SENTENCE.split(narration.asText().trim())) { String c = NarrationScrub.clean(clean(s)); if (!c.isBlank()) out.add(c); }
        }
        if (out.isEmpty()) out.add("...");
        return out;
    }

    // =================================================================== plan (reference tutorial style)

    /**
     * Reference-video lessons: one outline for the whole lesson (running example + cast + scene list), then the scenes are
     * written in batches of five/six so even a 10-minute lesson fits a local model. Every written scene is resolved against
     * the previous one (kept elements, positions) and stored, fully resolved, as the scene's plan JSON.
     */
    private void planTutorial(ConceptExplainerJob job) {
        boolean reel = isShort(job), bd = isBreakdown(job);
        TutorialPrompts.Size size = reel ? TutorialPrompts.shortSize() : bd ? TutorialPrompts.breakdownSize() : TutorialPrompts.size(job.getDuration());
        int craftSeconds = (int) Math.round((size.minWords() + size.maxWords()) / 2.0 * 60.0 / (bd ? 175.0 : 155.0));
        boolean manimOn = job.isUseManim() && manimAvailable();        // animated maths scenes only when the service is really there
        job.setManimActive(manimOn);
        if (job.isUseManim() && !manimOn && mathPhysics(job))
            job.getWarnings().add("Animated maths / physics scenes (Manim) were NOT used: the Manim service is not running. Start it with: docker compose --profile manim up -d --build manim - then make the lesson again.");
        String model = blankToNull(job.getModel());
        String topic = TeachingPlanner.cleanTopic(job.getTopic());
        job.setStage(reel ? "Planning the 60-second short" : bd ? "Planning the real-world breakdown" : "Planning the lesson and its running example");
        // a different teaching approach per lesson (seeded by the lesson id), so lessons do not all follow the same arc
        String approach = TutorialPrompts.approachFor(job.getId().getLeastSignificantBits() ^ System.nanoTime());
        JsonNode outline = readJson(gateway.llm().generateStructured(
                TutorialPrompts.withCraft(reel ? TutorialPrompts.shortOutlineSystemPrompt() : bd ? TutorialPrompts.breakdownOutlineSystemPrompt() : TutorialPrompts.outlineSystemPrompt(), craftSeconds, manimOn),
                reel ? TutorialPrompts.shortOutlineUserPrompt(topic, job.getLanguage(), job.getDifficulty(), job.getInstructions(), trackInstruction(job), size, approach)
                     : bd ? TutorialPrompts.breakdownOutlineUserPrompt(topic, job.getLanguage(), job.getDifficulty(), job.getInstructions(), trackInstruction(job), size)
                     : TutorialPrompts.outlineUserPrompt(topic, job.getLanguage(), job.getDifficulty(), job.getInstructions(), trackInstruction(job), size, approach), model));
        JsonNode planned = outline.path("scenes");
        if (!planned.isArray() || planned.size() < 3) {
            throw new IllegalStateException("The lesson planner returned no usable outline (needs at least 3 scenes).");
        }
        int total = Math.min(planned.size(), size.maxScenes() + 4);
        job.setTitle(text(outline, "title", topic));
        job.setSummary(text(outline, "summary", "A visual lesson on " + topic));
        job.setDomain(detectDomain(text(outline, "domain", null), topic));
        if (kids(job)) job.setIllustrationCast(text(outline, "characters", null));
        if (kids(job)) {
            int b = ageBand(job);
            job.getWarnings().add(b == 1 ? "Ages 2-5: illustrated scenes with Pip's robot-buddy world, one thing at a time, slow calm pace, long answer pauses. Best watched together with a grown-up."
                    : b == 3 ? "Ages 10-15: illustrated maker-studio scenes, a curiosity hook, challenges and levels, respectful tone."
                    : "Ages 5-10: illustrated scenes with the child hero and robot buddy, a mystery to solve, questions with answer pauses, quiz + badge.");
            if (b == 1 && !String.valueOf(job.getDuration()).startsWith("1") && !isShort(job))
                job.getWarnings().add("For ages 2-5, a 1-minute lesson works best; longer lessons ask a lot of a young child's attention.");
        }
        else if ("reference".equals(job.getStyle()) && healthDomain(job.getDomain()) && !reel && !bd)
            job.getWarnings().add("Health topic detected - drawn in the clinical look with anatomy icons (pick another visual style to override).");
        job.getScenes().clear();

        String outlineText = outline.toString();
        List<JsonNode> previous = new ArrayList<>();
        String lastLine = "";
        int words = 0, batch = reel ? total : total > 12 ? 5 : 6, dropped = 0;
        for (int from = 1; from <= total; from += batch) {
            int to = Math.min(total, from + batch - 1);
            job.setStage("Writing scenes " + from + "-" + to + " of " + total + " (narration + diagrams)");
            int target = (int) Math.round((size.minWords() + size.maxWords()) / 2.0 * (to - from + 1) / total);
            JsonNode written = null;
            for (int attempt = 0; attempt < 2 && written == null; attempt++) {
                try {
                    JsonNode w = readJson(gateway.llm().generateStructured(
                            TutorialPrompts.withCraft(reel ? TutorialPrompts.shortWriteSystemPrompt() : bd ? TutorialPrompts.breakdownWriteSystemPrompt() : TutorialPrompts.writeSystemPrompt(), craftSeconds, manimOn),
                            TutorialPrompts.writeUserPrompt(topic, job.getLanguage(), outlineText, from, to, total,
                                    DiagramSpec.describe(previous), lastLine, target) + "\n\nTRACK RULES (apply to every scene you write):\n" + trackInstruction(job), model));
                    if (w.path("scenes").isArray() && !w.path("scenes").isEmpty()) written = w;
                } catch (Exception e) {
                    if (attempt == 1) throw e;
                }
            }
            if (written == null) throw new IllegalStateException("The model wrote no scenes for " + from + "-" + to + ".");
            if (reel) written = fillSixtySeconds(job, written, topic, outlineText, total, target, size, model);
            int taken = 0;
            for (JsonNode sc : written.path("scenes")) {
                if (taken >= to - from + 1) break;
                List<String> sentences = rawSentences(sc.path("narration"));
                if (sentences.isEmpty()) { dropped++; continue; }
                taken++;
                List<String> notes = new ArrayList<>();
                ObjectNode resolved = DiagramSpec.resolve(sc, previous, sentences.size(), notes);
                if (kids(job)) ensureScenePicture(resolved, sc, sentences);
                int number = job.getScenes().size() + 1;
                JsonNode outlineScene = planned.path(Math.min(planned.size() - 1, number - 1));
                ConceptExplainerJob.Scene s = new ConceptExplainerJob.Scene();
                s.setSceneNumber(number);
                s.setTemplate("diagram");
                s.setTitle(text(sc, "title", text(sc, "heading", text(outlineScene, "keyIdea", "Scene " + number))));
                s.setSentences(new ArrayList<>(sentences));
                s.setNarration(String.join(" ", sentences));
                s.setSectionType(text(outlineScene, "section", null));
                s.setLearningObjective(text(outlineScene, "keyIdea", null));
                s.setVisualDescription(text(outlineScene, "screen", null));
                s.setVoiceDirection("confident", "normal", "calm conversational instructor");
                ObjectNode mz = manimFor(job, sc, job.getScenes().size() + 1);
                if (mz != null) resolved.set("manim", mz);
                s.setPlanJson(resolved.toString());
                applyVoicePlan(s, sc, kids(job));
                List<String> caps = rawSentences(sc.path("captions"));
                if (caps.size() == sentences.size()) s.setCaptions(new ArrayList<>(caps));
                job.getScenes().add(s);
                previous = new ArrayList<>();
                resolved.path("elements").forEach(previous::add);
                lastLine = sentences.get(sentences.size() - 1);
                for (String x : sentences) words += x.trim().isEmpty() ? 0 : x.trim().split("\\s+").length;
                if (!notes.isEmpty()) job.getWarnings().add("Scene " + number + ": " + String.join("; ", notes));
            }
        }
        if (reel) words += ensureShortBookends(job, outline, topic);
        if (!job.isReviewScript()) shortenToBudget(job, model);        // nobody reads the draft in this mode: keep it inside its word budget
        suggestManimScenes(job, model);
        enforceNarrationScript(job, model);           // Indian-language narration must be in its own script, or no voice can read it
        fixRomanizedOnScreenText(job, model);         // on-screen text is English; romanised Hindi there is translated
        ensureEnglishCaptions(job, model);
        if (job.getScenes().size() < 3) throw new IllegalStateException("The lesson came back with fewer than 3 usable scenes - try again or pick another model.");
        reportVariety(job, bd ? null : approach, reel ? 4 : bd ? 6 : size.maxScenes() >= 30 ? 9 : size.maxScenes() >= 15 ? 6 : 4);
        if (dropped > 0) job.getWarnings().add(dropped + " scene(s) without narration were skipped.");
        job.setWordCount(words);
        if (words < size.minWords() * 0.75 || words > size.maxWords() * 1.3) {
            job.getWarnings().add("Lesson has " + words + " spoken words (target " + size.minWords() + "-" + size.maxWords() + "), so it will run "
                    + (words < size.minWords() ? "short" : "long") + ".");
        }
    }

    // =================================================================== word budget, automatically

    /**
     * Without the review step nobody reads the script, so a draft that is far over its word budget is shortened here: the longest
     * scenes are asked to give up the excess (same number of sentences, same facts). Two rounds at most; the review step stays optional.
     */
    private void shortenToBudget(ConceptExplainerJob job, String model) {
        TutorialPrompts.Size size = sizeFor(job);
        for (int round = 0; round < 2; round++) {
            int words = 0;
            for (ConceptExplainerJob.Scene s : job.getScenes()) for (String l : s.getSentences()) words += l.trim().isEmpty() ? 0 : l.trim().split("\\s+").length;
            int excess = words - size.maxWords();
            if (excess <= size.maxWords() * 0.06) return;
            job.setStage("The script is " + excess + " words over its budget - shortening the longest scenes");
            List<ConceptExplainerJob.Scene> longest = new ArrayList<>(job.getScenes());
            longest.sort((a, b) -> b.getNarration().length() - a.getNarration().length());
            longest = longest.subList(0, Math.min(4, longest.size()));
            ArrayNode arr = mapper.createArrayNode();
            for (ConceptExplainerJob.Scene s : longest) {
                ObjectNode o = arr.addObject();
                o.put("scene", s.getSceneNumber());
                o.putArray("problems").add("too-long: shorten this scene");
                ArrayNode na = o.putArray("narration");
                s.getSentences().forEach(na::add);
            }
            int changed = 0;
            try {
                JsonNode out = readJson(gateway.llm().generateStructured(TutorialPrompts.doctorSystemPrompt(), TutorialPrompts.doctorUserPrompt(arr.toString(), excess), model));
                for (JsonNode sc : out.path("scenes")) {
                    for (ConceptExplainerJob.Scene s : longest) {
                        if (s.getSceneNumber() != sc.path("scene").asInt(-1)) continue;
                        List<String> fixed = rawSentences(sc.path("narration"));
                        if (fixed.size() == s.getSentences().size()) {
                            s.setSentences(new ArrayList<>(fixed));
                            s.setNarration(String.join(" ", fixed));
                            s.setCaptions(new ArrayList<>());
                            changed++;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Automatic shortening failed: {}", e.getMessage());
            }
            if (changed == 0) return;
        }
    }

    // =================================================================== Indian-language script guard

    /**
     * Hindi (or Marathi, Kannada ...) narration must be written in its own script. A small local model often answers in Latin letters
     * ("Ek cricket ball ke haath se ..."); a Hindi voice reads that as English-sounding gibberish. Scenes with romanised lines are rewritten
     * by the model in the right script (two tries, same number of lines); anything that still fails is reported.
     */
    private void enforceNarrationScript(ConceptExplainerJob job, String model) {
        String lang = job.getLanguage();
        if (!NarrationScript.expectsNativeScript(lang)) return;
        String script = NarrationScript.scriptName(lang);
        for (int attempt = 0; attempt < 2; attempt++) {
            List<ConceptExplainerJob.Scene> bad = new ArrayList<>();
            for (ConceptExplainerJob.Scene sc : job.getScenes()) if (NarrationScript.countRomanized(lang, sc.getSentences()) > 0) bad.add(sc);
            if (bad.isEmpty()) return;
            job.setStage("Rewriting the " + lang + " narration in " + script + " script (" + bad.size() + " scene(s) were in Latin letters)");
            for (int from = 0; from < bad.size(); from += 6) {
                List<ConceptExplainerJob.Scene> batch = bad.subList(from, Math.min(bad.size(), from + 6));
                ArrayNode arr = mapper.createArrayNode();
                for (ConceptExplainerJob.Scene sc : batch) {
                    ObjectNode o = arr.addObject();
                    o.put("scene", sc.getSceneNumber());
                    ArrayNode lines = o.putArray("lines");
                    sc.getSentences().forEach(lines::add);
                }
                try {
                    JsonNode out = readJson(gateway.llm().generateStructured(NarrationScript.repairSystemPrompt(lang), NarrationScript.repairUserPrompt(arr.toString()), model)).path("scenes");
                    for (JsonNode n : out) {
                        int no = n.path("scene").asInt(-1);
                        for (ConceptExplainerJob.Scene sc : batch) {
                            if (sc.getSceneNumber() != no) continue;
                            List<String> lines = rawSentences(n.path("lines"));
                            if (lines.size() == sc.getSentences().size() && NarrationScript.countRomanized(lang, lines) == 0) {
                                sc.setSentences(new ArrayList<>(lines));
                                sc.setNarration(String.join(" ", lines));
                                sc.setCaptions(new ArrayList<>());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Script repair for {} failed: {}", lang, e.getMessage());
                }
            }
        }
        long still = job.getScenes().stream().filter(sc -> NarrationScript.countRomanized(lang, sc.getSentences()) > 0).count();
        if (still > 0) job.getWarnings().add(still + " scene(s) are still written in Latin letters instead of " + script + " - a " + lang + " voice cannot pronounce them properly. "
                + "Use a larger language model, or edit those lines at the script review step.");
    }

    /** On-screen text is meant to be English; romanised Hindi / Marathi that slipped in (a hook callout quoting the narration) is translated. */
    private void fixRomanizedOnScreenText(ConceptExplainerJob job, String model) {
        if (TutorialPrompts.english(job.getLanguage())) return;
        List<ConceptExplainerJob.Scene> owners = new ArrayList<>();
        List<ObjectNode> roots = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<java.util.function.Consumer<String>> setters = new ArrayList<>();
        List<Integer> ownerIndex = new ArrayList<>();
        try {
            for (ConceptExplainerJob.Scene sc : job.getScenes()) {
                if (sc.getPlanJson() == null) continue;
                JsonNode parsed = mapper.readTree(sc.getPlanJson());
                if (!(parsed instanceof ObjectNode root)) continue;
                int oi = owners.size();
                owners.add(sc);
                roots.add(root);
                for (String f : List.of("heading", "label")) {
                    if (root.path(f).isTextual() && NarrationScript.looksRomanizedIndic(root.path(f).asText())) {
                        texts.add(root.path(f).asText()); ownerIndex.add(oi);
                        final String fld = f;
                        setters.add(t -> root.put(fld, t));
                    }
                }
                for (JsonNode el : root.path("elements")) {
                    if (!(el instanceof ObjectNode eo)) continue;
                    for (String f : List.of("text", "title", "label", "caption", "content")) {
                        if (eo.path(f).isTextual() && NarrationScript.looksRomanizedIndic(eo.path(f).asText())) {
                            texts.add(eo.path(f).asText()); ownerIndex.add(oi);
                            final String fld = f;
                            setters.add(t -> eo.put(fld, t));
                        }
                    }
                    if (eo.path("lines").isArray()) {
                        ArrayNode ln = (ArrayNode) eo.path("lines");
                        for (int i = 0; i < ln.size(); i++) {
                            if (ln.get(i).isTextual() && NarrationScript.looksRomanizedIndic(ln.get(i).asText())) {
                                texts.add(ln.get(i).asText()); ownerIndex.add(oi);
                                final int idx = i;
                                setters.add(t -> ln.set(idx, t));
                            }
                        }
                    }
                }
            }
            if (texts.isEmpty()) return;
            job.setStage("Translating on-screen text that was written in romanised " + job.getLanguage());
            ArrayNode in = mapper.createArrayNode();
            texts.forEach(in::add);
            JsonNode out = readJson(gateway.llm().generateStructured(NarrationScript.translateOnScreenSystemPrompt(), "Texts:\n" + in, model)).path("texts");
            int done = 0;
            if (out.isArray() && out.size() == texts.size()) {
                for (int i = 0; i < texts.size(); i++) {
                    String t = out.get(i).asText("").trim();
                    if (!t.isEmpty() && !NarrationScript.looksRomanizedIndic(t)) { setters.get(i).accept(t); done++; }
                }
            }
            java.util.Set<Integer> touched = new java.util.HashSet<>(ownerIndex);
            for (int oi : touched) owners.get(oi).setPlanJson(roots.get(oi).toString());
            if (done < texts.size()) job.getWarnings().add((texts.size() - done) + " on-screen text(s) are still in romanised " + job.getLanguage() + " - they should be English.");
        } catch (Exception e) {
            log.warn("On-screen text repair failed: {}", e.getMessage());
            job.getWarnings().add("Some on-screen text may be in romanised " + job.getLanguage() + " (the automatic English rewrite failed).");
        }
    }

    // =================================================================== animated maths / physics (Manim)

    private static boolean mathPhysics(ConceptExplainerJob job) {
        String subj = job.getSubject() == null ? "" : job.getSubject().toLowerCase(Locale.ROOT);
        return subj.contains("physic") || subj.contains("math");
    }

    /**
     * Physics and maths lessons should get real animations, but a small model rarely volunteers the optional "manim" object. When the
     * service is up and the draft has none, ONE focused question picks the scenes (at most a quarter of them) and the template and numbers
     * for each; every answer is validated against the service like any other, and a bad one is simply dropped.
     */
    private void suggestManimScenes(ConceptExplainerJob job, String model) {
        if (!job.isManimActive() || !mathPhysics(job)) return;
        for (ConceptExplainerJob.Scene s : job.getScenes()) if (manimSpec(s) != null) return;
        int max = Math.max(1, job.getScenes().size() / 4);
        job.setStage("Choosing the scenes that deserve an animated diagram");
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (ConceptExplainerJob.Scene s : job.getScenes()) {
                ObjectNode o = arr.addObject();
                o.put("scene", s.getSceneNumber());
                o.put("title", s.getTitle() == null ? "" : s.getTitle());
                o.put("section", s.getSectionType() == null ? "" : s.getSectionType());
                String n = s.getNarration() == null ? "" : s.getNarration();
                o.put("narration", n.length() > 260 ? n.substring(0, 260) : n);
            }
            String system = "You choose which scenes of a physics / maths video deserve a real animated diagram (Manim). Pick AT MOST " + max + " scene(s): the ones where a moving "
                    + "graph, vectors, a trajectory, a wave or algebra steps teach the idea. Skip the hook, quiz, recap and purely verbal scenes. The numbers in the params must be "
                    + "correct for what the narration says. Return JSON only: {\"manim\":[{\"scene\":N,\"template\":\"...\",\"params\":{...}}]}\n\n" + TutorialPrompts.manimBlock();
            JsonNode out = readJson(gateway.llm().generateStructured(system, "Scenes:\n" + arr, model)).path("manim");
            int added = 0;
            for (JsonNode sug : out) {
                if (added >= max) break;
                int no = sug.path("scene").asInt(-1);
                if (no < 1 || no > job.getScenes().size()) continue;
                ObjectNode wrap = mapper.createObjectNode();
                ObjectNode m = wrap.putObject("manim");
                m.put("template", sug.path("template").asText(""));
                m.set("params", sug.path("params"));
                ObjectNode mz = manimFor(job, wrap, no);                     // validated by the service; a bad formula is dropped with a note
                if (mz == null) continue;
                ConceptExplainerJob.Scene sc = job.getScenes().get(no - 1);
                JsonNode parsed = mapper.readTree(sc.getPlanJson() == null ? "{}" : sc.getPlanJson());
                if (!(parsed instanceof ObjectNode root)) continue;
                root.set("manim", mz);
                root.putArray("elements");                                    // the animation fills the frame; the heading titles it
                sc.setPlanJson(root.toString());
                added++;
            }
            if (added > 0) job.getWarnings().add(added + " scene(s) were turned into animated diagrams (Manim).");
        } catch (Exception e) {
            log.warn("Manim suggestions failed: {}", e.getMessage());
        }
    }

    /** The scene's "manim" request, checked against the sidecar NOW (a bad formula is dropped with a note, before any render time). */
    private ObjectNode manimFor(ConceptExplainerJob job, JsonNode sc, int sceneNo) {
        JsonNode raw = sc.path("manim");
        if (!raw.isObject()) return null;
        if (!job.isManimActive()) {
            job.getWarnings().add("Scene " + sceneNo + " asked for an animated maths scene, but the Manim service is not running - start it with: docker compose --profile manim up -d --build manim");
            return null;
        }
        String template = raw.path("template").asText("");
        JsonNode params = raw.path("params");
        if (!com.aistorystudio.manim.ManimClient.TEMPLATES.contains(template) || !params.isObject() || params.toString().length() > 4000) {
            job.getWarnings().add("Scene " + sceneNo + ": the maths animation was left out (unknown template or parameters).");
            return null;
        }
        String err = manim.validate(template, params, 10);
        if (err != null) {
            job.getWarnings().add("Scene " + sceneNo + ": the maths animation was left out (" + err + ").");
            return null;
        }
        ObjectNode o = mapper.createObjectNode();
        o.put("template", template);
        o.set("params", params);
        return o;
    }

    private JsonNode manimSpec(ConceptExplainerJob.Scene s) {
        if (manim == null) return null;
        try {
            JsonNode m = mapper.readTree(s.getPlanJson() == null ? "{}" : s.getPlanJson()).path("manim");
            return m.isObject() ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The finished frame of the scene's animation (about a second to render): the backdrop for the slide, storyboard and fallback. */
    private String manimStill(ConceptExplainerJob job, ConceptExplainerJob.Scene s) {
        JsonNode mz = manimSpec(s);
        if (mz == null) return null;
        try {
            byte[] png = manim.render(mz.path("template").asText(), mz.path("params"), 8, vertical(job) ? 720 : 1280, vertical(job) ? 1280 : 720, theme(job), true);
            return storage.store(dir(job) + "/manim/scene-" + num(s) + "-still-v" + s.getImageVersion() + ".png", png).toString();
        } catch (Exception e) {
            job.getWarnings().add("Scene " + s.getSceneNumber() + ": the maths animation could not be drawn (" + rootMessage(e) + ") - the slide is shown without it.");
            return null;
        }
    }

    /** After the narration (its length is known): renders the animation paced to the voice; the clip builder plays it under the overlays. */
    private void renderManimAnimation(ConceptExplainerJob job, ConceptExplainerJob.Scene s) {
        JsonNode mz = manimSpec(s);
        if (mz == null || s.getBackdropPath() == null) return;
        try {
            double secs = Math.max(2.5, Math.min(60, s.getDurationSeconds()));
            byte[] mp4 = manim.render(mz.path("template").asText(), mz.path("params"), secs, vertical(job) ? 720 : 1280, vertical(job) ? 1280 : 720, theme(job), false);
            s.setAnimationPath(storage.store(dir(job) + "/manim/scene-" + num(s) + "-a" + s.getAudioVersion() + ".mp4", mp4).toString());
        } catch (Exception e) {
            s.setAnimationPath(null);
            job.getWarnings().add("Scene " + s.getSceneNumber() + ": the maths animation failed (" + rootMessage(e) + ") - its final picture is shown instead.");
        }
    }

    /**
     * The scene's voice plan from the model's "delivery" and "camera": a pause after each sentence (silence after the reveal),
     * words to stress, pace / energy. If the model gave no pauses, default silence beats are placed after reveal sentences.
     */
    private void applyVoicePlan(ConceptExplainerJob.Scene s, JsonNode sc, boolean kidsMode) {
        JsonNode d = sc.path("delivery");
        int n = s.getSentences().size();
        List<Double> pauses = new ArrayList<>();
        JsonNode pa = d.path("pauseAfter");
        if (pa.isArray()) for (int i = 0; i < n; i++) pauses.add(i < pa.size() ? Math.max(0, Math.min(2.5, pa.get(i).asDouble(0))) : 0.0);
        if (pauses.stream().noneMatch(x -> x > 0.05)) pauses = new ArrayList<>(com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.silenceBeats(s.getSentences(), kidsMode));
        double total = 0;                                     // at most 3 s of added silence in a scene
        for (int i = 0; i < pauses.size(); i++) { total += pauses.get(i); if (total > 3.0) pauses.set(i, 0.0); }
        s.setPauseAfter(pauses);
        List<String> em = new ArrayList<>();
        if (d.path("emphasis").isArray()) for (JsonNode e : d.path("emphasis")) if (!e.asText("").isBlank() && em.size() < 4) em.add(e.asText().trim());
        s.setEmphasis(em);
        s.setCamera(text(sc, "camera", null));
        s.setDeliveryNote(text(d, "note", null));
        String pace = text(d, "pace", null), energy = text(d, "energy", null);
        if (pace != null || energy != null) {
            String p = pace == null ? "normal" : pace.equalsIgnoreCase("brisk") ? "fast" : pace.equalsIgnoreCase("measured") ? "normal" : pace;
            s.setVoiceDirection(energy == null ? "confident" : energy, p, text(d, "note", "calm conversational instructor"));
        }
    }

    /** A note when the finished narration is still dull (the polish step already lifts a dull voice; this catches what it could not fix). */
    private void checkVoiceClarity(ConceptExplainerJob job) {
        List<Double> ratios = new ArrayList<>();
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            try {
                if (s.getAudioPath() == null) continue;
                double r = com.aistorystudio.qa.VoiceQa.highRatioDb(Files.readAllBytes(Path.of(s.getAudioPath())));
                if (!Double.isNaN(r)) ratios.add(r);
            } catch (Exception ignored) { }
        }
        if (ratios.size() < 2) return;
        java.util.Collections.sort(ratios);
        double median = ratios.get(ratios.size() / 2);
        if (median < com.aistorystudio.qa.VoiceQa.MUFFLED_BELOW_DB)
            job.getWarnings().add(String.format(Locale.ROOT, "The narration sounds muffled (%.0f dB of 4-8 kHz against the voice body; a clear voice is about -15 to -20). Voice requested: %s. "
                    + "A different voice (for Hindi and other Indian languages the Edge voices Madhur / Swara are the clearest) usually fixes it.", median, job.getVoice() == null ? "default" : job.getVoice()));
    }

    private static final double SHORT_BUDGET_SECONDS = 62;

    /**
     * A reel is meant to be about a minute. The word budget alone cannot promise that (gaps, cards, the voice's real pace), so once every
     * scene's narration is recorded the total is measured; if it is clearly over, every scene's audio is sped up by the same factor
     * (pitch preserved, at most 1.3x) and its timings rescaled.
     */
    private void fitToBudget(ConceptExplainerJob job) {
        double total = 0;
        for (ConceptExplainerJob.Scene s : job.getScenes()) total += s.getDurationSeconds();
        total -= Math.max(0, job.getScenes().size() - 1) * fade(job);          // the cross-fades overlap
        if (total <= SHORT_BUDGET_SECONDS * 1.06) return;
        double factor = Math.min(1.3, total / SHORT_BUDGET_SECONDS);
        job.setStage(String.format(Locale.ROOT, "Fitting the short to about a minute (it was %.0f s): narration %.0f%% faster", total, (factor - 1) * 100));
        int failed = 0;
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            try { retimeScene(job, s, factor); } catch (Exception e) { failed++; log.warn("Retiming scene {} failed: {}", s.getSceneNumber(), e.getMessage()); }
        }
        double after = 0;
        for (ConceptExplainerJob.Scene s : job.getScenes()) after += s.getDurationSeconds();
        after -= Math.max(0, job.getScenes().size() - 1) * fade(job);
        job.getWarnings().add(String.format(Locale.ROOT, "The short was %.0f s, so the narration was sped up %.0f%% to bring it to about %.0f s.%s", total, (factor - 1) * 100, after,
                failed > 0 ? " (" + failed + " scene(s) could not be sped up.)" : ""));
    }

    /** Speeds one scene's recorded audio up by {@code factor} (pitch preserved) and rescales its sentence timings and slide steps. */
    private void retimeScene(ConceptExplainerJob job, ConceptExplainerJob.Scene s, double factor) throws IOException, InterruptedException {
        if (s.getAudioPath() == null || factor <= 1.005) return;
        Path tmp = Files.createTempFile("retime-", ".wav");
        try {
            String[] filters = {String.format(Locale.ROOT, "rubberband=tempo=%.4f:formant=preserved:pitchq=quality:transients=smooth", factor), String.format(Locale.ROOT, "atempo=%.4f", factor)};
            boolean ok = false;
            for (String f : filters) {
                try {
                    ffmpeg(new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", s.getAudioPath(), "-af", f, "-ar", "48000", "-ac", "2", tmp.toString())));
                    if (Files.size(tmp) > 1000) { ok = true; break; }
                } catch (IOException e) { /* rubberband missing in this ffmpeg build: try atempo */ }
            }
            if (!ok) throw new IOException("no time-stretch filter worked");
            s.bumpAudioVersion();
            Path out = storage.store(dir(job) + "/audio/scene-" + num(s) + "-v" + s.getAudioVersion() + ".wav", Files.readAllBytes(tmp));
            s.setAudioPath(out.toString());
            double speech = media.probeDurationSeconds(out);
            List<Double> starts = new ArrayList<>(), lengths = new ArrayList<>();
            for (double v : s.getSentenceStarts()) starts.add(v / factor);
            for (double v : s.getSentenceLengths()) lengths.add(v / factor);
            double fallback = (starts.isEmpty() ? 0 : starts.get(starts.size() - 1) + lengths.get(lengths.size() - 1));
            s.setDurationSeconds((speech > 0 ? speech : fallback) + tail(job));
            s.setSentenceTiming(starts, lengths);
            s.setStepTimes(stepTimes(starts, s.getStepPaths().size(), speech > 0 ? speech : fallback, job));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Pause after sentence i: the larger of the format's normal gap and the planned silence beat (a beat never shortens a gap). */
    /** How much of the natural pace/pause variation to apply: toddlers and 60-second reels get less, lessons get all of it. */
    private static double pacingFactor(ConceptExplainerJob job) {
        int band = ageBand(job);
        if (band == 1) return 0.4;
        if (band == 2) return 0.7;
        return vertical(job) ? 0.75 : 1.0;
    }

    private double gapAt(ConceptExplainerJob job, ConceptExplainerJob.Scene s, int i) {
        double base = gap(job, s.getSentences().get(i))
                + com.aistorystudio.conceptexplainer.speech.SpeechPacing.plan(s.getSentences().get(i), i, s.getSentences().size()).scaled(pacingFactor(job)).gapAdd();
        List<Double> p = s.getPauseAfter();
        double planned = i < p.size() ? p.get(i) : 0.0;
        return Math.max(base, isShort(job) ? planned * 0.3 : planned);       // a 60-second short cannot afford the reveal pauses of a lesson
    }

    /** The planned stress words that actually occur in this sentence. */
    private static List<String> emphasisFor(ConceptExplainerJob.Scene s, String line) {
        List<String> out = new ArrayList<>();
        String low = line.toLowerCase(Locale.ROOT);
        for (String w : s.getEmphasis()) if (low.contains(w.toLowerCase(Locale.ROOT))) out.add(w);
        return out;
    }

    /**
     * Shorts always open on the title card and close on the outro card (as both reference reels do). If the model
     * forgot one, it is added here: the opening asks the hook question, the closing says the tagline.
     * Returns the number of spoken words added.
     */
    private int ensureShortBookends(ConceptExplainerJob job, JsonNode outline, String topic) {
        int added = 0;
        List<ConceptExplainerJob.Scene> sc = job.getScenes();
        if (sc.isEmpty() || !sc.get(0).getPlanJson().contains("\"titlecard\"")) {
            String hook = text(outline, "hook", topic + "?");
            ObjectNode plan = mapper.createObjectNode();
            plan.put("heading", "");
            plan.put("label", "");
            ObjectNode tc = plan.putArray("elements").addObject();
            tc.put("id", "titlecard"); tc.put("type", "titlecard"); tc.put("at", 0); tc.put("dimAt", -1);
            tc.put("title", text(outline, "subject", job.getSubject() == null || job.getSubject().equals("General") ? "Explained" : job.getSubject()));
            tc.put("text", text(outline, "title", topic));
            tc.put("content", text(outline, "summary", ""));
            tc.put("style", "Learn one concept a day");
            tc.put("x", 50); tc.put("y", 42);
            sc.add(0, bookend(plan, hook, "Title"));
            added += hook.split("\\s+").length;
        }
        ConceptExplainerJob.Scene last = sc.get(sc.size() - 1);
        if (last.getPlanJson().contains("\"outro\"")) {
            // the model's own outro card: always the page's call to follow
            try {
                ObjectNode plan = (ObjectNode) mapper.readTree(last.getPlanJson());
                for (JsonNode e : plan.withArray("elements"))
                    if ("outro".equals(e.path("type").asText())) { ((ObjectNode) e).put("text", TutorialPrompts.SHORT_TAGLINE); ((ObjectNode) e).put("title", "Follow"); }
                last.setPlanJson(plan.toString());
            } catch (Exception ignored) { }
        } else {
            ObjectNode plan = mapper.createObjectNode();
            plan.put("heading", "");
            plan.put("label", "");
            ObjectNode o = plan.putArray("elements").addObject();
            o.put("id", "outro"); o.put("type", "outro"); o.put("at", 0); o.put("dimAt", -1);
            o.put("text", TutorialPrompts.SHORT_TAGLINE); o.put("title", "Follow"); o.put("x", 50); o.put("y", 42);
            sc.add(bookend(plan, TutorialPrompts.SHORT_TAGLINE, "Outro"));
            added += TutorialPrompts.SHORT_TAGLINE.split(" ").length;
        }
        for (int i = 0; i < sc.size(); i++) sc.get(i).setSceneNumber(i + 1);
        return added;
    }

    /**
     * Subtitles are always English. English narration: captions = narration. Otherwise every scene whose model output
     * had no matching "captions" list is translated in ONE extra call (sentence for sentence, so timing stays exact).
     */
    private void ensureEnglishCaptions(ConceptExplainerJob job, String model) {
        if (TutorialPrompts.english(job.getLanguage())) {
            for (ConceptExplainerJob.Scene s : job.getScenes()) s.setCaptions(new ArrayList<>(s.getSentences()));
            return;
        }
        List<ConceptExplainerJob.Scene> missing = new ArrayList<>();
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            if (s.getCaptions().size() != s.getSentences().size()) {
                if (s.getSentences().size() == 1 && s.getSentences().get(0).equals(TutorialPrompts.SHORT_TAGLINE)) s.setCaptions(new ArrayList<>(s.getSentences()));
                else missing.add(s);
            }
        }
        if (missing.isEmpty()) return;
        job.setStage("Writing English subtitles");
        try {
            ArrayNode in = mapper.createArrayNode();
            for (ConceptExplainerJob.Scene s : missing) {
                ArrayNode a = in.addArray();
                s.getSentences().forEach(a::add);
            }
            JsonNode out = readJson(gateway.llm().generateStructured(
                    "You translate narration into natural, short English subtitles. Keep the meaning, keep numbers and technical terms, "
                            + "one English sentence for each input sentence, same order, same count. Return JSON only: {\"captions\": [[...], [...]]}",
                    "Narration language: " + job.getLanguage() + "\nScenes (each an array of sentences):\n" + in, model)).path("captions");
            for (int i = 0; i < missing.size() && i < out.size(); i++) {
                List<String> caps = rawSentences(out.get(i));
                if (caps.size() == missing.get(i).getSentences().size()) missing.get(i).setCaptions(new ArrayList<>(caps));
            }
        } catch (Exception e) {
            log.warn("English subtitle translation failed: {}", e.getMessage());
        }
        long still = job.getScenes().stream().filter(s -> s.getCaptions().size() != s.getSentences().size()).count();
        if (still > 0) job.getWarnings().add(still + " scene(s) have no English subtitles (translation failed) - they show no captions.");
    }

    /**
     * Breakdown reels open on (and revisit) a picture of the real-world example. Each "photo" element's description is
     * rendered by the image model in the 3D-blueprint look; the file path is written into the scene plan so re-renders
     * reuse it. A failed picture is simply dropped (the 3D room behind it is still drawn).
     */
    private void generatePhotos(ConceptExplainerJob job) {
        int made = 0, cap = kids(job) ? 16 : isBreakdown(job) ? 5 : 6;
        String castRef = job.getCastReferencePath();   // kids: the first picture becomes the character reference for every later scene
        for (ConceptExplainerJob.Scene s : job.getScenes()) {
            try {
                ObjectNode plan = (ObjectNode) mapper.readTree(s.getPlanJson());
                boolean changed = false;
                var it = plan.withArray("elements").elements();
                List<Integer> drop = new ArrayList<>();
                int idx = 0;
                while (it.hasNext()) {
                    ObjectNode e = (ObjectNode) it.next();
                    if ("photo".equals(e.path("type").asText()) && blank(e.path("content").asText(null))) {
                        if (!isBreakdown(job) && !kids(job)) {   // lessons: a framed picture next to the diagram, not a backdrop
                            if (!e.path("style").asText("").contains("framed")) e.put("style", "framed");
                            if (!e.has("w")) e.put("w", vertical(job) ? 70 : 34);
                            if (!e.has("h")) e.put("h", vertical(job) ? 30 : 52);
                        }
                        if (made >= cap) { drop.add(idx); idx++; continue; }
                        job.setStage("Rendering the real-world picture for scene " + s.getSceneNumber());
                        String what = e.path("text").asText("the real-world example");
                        String prompt = kids(job) ? kidsScenePrompt(job, what, castRef != null) : !isBreakdown(job) ? illustrationPrompt(job, what) : "Vertical cinematic 3D render for a premium tech explainer: " + what + ". "
                                + "Stylised 3D blueprint world: deep navy-blue palette, glowing cyan-white edges and window light, soft volumetric fog, "
                                + "night-time, physically based materials, depth of field, high detail, dramatic perspective, centred subject, "
                                + "a little warm yellow accent light. No people's faces in close-up. ABSOLUTELY NO TEXT anywhere: no letters, no numbers, "
                                + "no signs, no screens with writing, no UI, no labels, no logos, no watermark (all words are added later as overlays).";
                        try {
                            int pw = isBreakdown(job) || vertical(job) ? 768 : kids(job) ? 1344 : 1152, ph = isBreakdown(job) || vertical(job) ? 1344 : kids(job) ? 768 : 864;
                            boolean chained = kids(job) && castRef != null;
                            var r = gateway.generateImage(new ImageGenerationProvider.ImageGenerationRequest(prompt,
                                    kids(job) ? "text, letters, words, numbers, logo, watermark, scary, horror, dark gloomy, violence, weapons, distorted face, extra fingers, deformed hands, low detail, blurry, photo-realistic skin, uncanny"
                                              : "text, letters, words, numbers, digits, signage, captions, user interface, screen text, logo, watermark, cartoon, flat illustration, low detail, blurry, oversaturated, bright daylight",
                                    pw, ph, chained ? 30 : 28, chained ? 0 : 4.0, null, chained ? "qwen-image-2-1-16gb-ref" : "qwen-image-2-1-16gb-t2i", null,
                                    chained ? castRef : null, null));
                            Path p = storage.store(dir(job) + "/photos/scene-" + num(s) + "-" + e.path("id").asText("photo") + ".png", r.imageBytes());
                            e.put("content", p.toString());
                            if (kids(job) && castRef == null) { castRef = p.toString(); job.setCastReferencePath(castRef); }
                            made++;
                        } catch (Exception ex) {
                            drop.add(idx);
                            job.getWarnings().add("Scene " + s.getSceneNumber() + ": real-world picture failed (" + rootMessage(ex) + ").");
                        }
                        changed = true;
                    }
                    idx++;
                }
                for (int k = drop.size() - 1; k >= 0; k--) plan.withArray("elements").remove(drop.get(k));
                if (changed || !drop.isEmpty()) s.setPlanJson(plan.toString());
            } catch (Exception ex) {
                log.warn("Photo step failed for scene {}: {}", s.getSceneNumber(), ex.getMessage());
            }
        }
    }

    /**
     * A short must really fill 60 seconds: local models often write 90-120 words (a 40-second, half-baked video). The
     * draft is sent back with its word count and asked to explain more (not pad) - up to twice - keeping the longest
     * draft that is not over the limit.
     */
    private JsonNode fillSixtySeconds(ConceptExplainerJob job, JsonNode written, String topic, String outlineText, int total, int target,
                                      TutorialPrompts.Size size, String model) {
        JsonNode best = written;
        int bestWords = narrationWords(written);
        for (int round = 0; round < 2 && bestWords < size.minWords() * 0.93; round++) {
            job.setStage("Short is " + bestWords + " words (~" + Math.round(bestWords / 2.6) + " s) - asking for a fuller 60-second script");
            try {
                String feedback = "\n\nYOUR PREVIOUS DRAFT (" + bestWords + " words, about " + Math.round(bestWords / 2.6) + " seconds - TOO SHORT, it felt half-baked):\n"
                        + best + "\n\nRewrite ALL scenes so the narration totals " + size.minWords() + "-" + size.maxWords() + " words (60 seconds). "
                        + "Do not pad: explain the mechanism more concretely on the example, add the missing step or the before/after, "
                        + "and add matching on-screen elements for the new sentences (update every \"at\"). Keep the title card first and the outro last.";
                JsonNode w = readJson(gateway.llm().generateStructured(TutorialPrompts.withCraft(TutorialPrompts.shortWriteSystemPrompt(), 60),
                        TutorialPrompts.writeUserPrompt(topic, job.getLanguage(), outlineText, 1, total, total, "", "", target) + feedback, model));
                int words = narrationWords(w);
                if (w.path("scenes").isArray() && w.path("scenes").size() >= 3 && words > bestWords && words <= size.maxWords() * 1.25) {
                    best = w;
                    bestWords = words;
                }
            } catch (Exception e) {
                log.warn("Short lengthening pass failed: {}", e.getMessage());
            }
        }
        if (bestWords < size.minWords() * 0.85)
            job.getWarnings().add("The model wrote only " + bestWords + " words - the short will run about " + Math.round(bestWords / 2.6)
                    + " s instead of 60. A larger model (14B+) follows the length rule better.");
        return best;
    }

    private static int narrationWords(JsonNode written) {
        int n = 0;
        for (JsonNode sc : written.path("scenes"))
            for (JsonNode line : sc.path("narration")) {
                String t = line.asText("").trim();
                if (!t.isEmpty()) n += t.split("\\s+").length;
            }
        return n;
    }

    /**
     * Kids lessons are picture books: every scene gets one full-screen illustration, whether or not the model remembered to
     * add it. The picture description comes from the scene's "picture" field, else the model's photo text, else the first line.
     */
    private void ensureScenePicture(ObjectNode resolved, JsonNode written, List<String> sentences) {
        var els = resolved.withArray("elements");
        for (JsonNode e : els) {
            if ("photo".equals(e.path("type").asText())) {
                ObjectNode pe = (ObjectNode) e;
                pe.put("style", "full"); pe.remove("x"); pe.remove("y"); pe.remove("w"); pe.remove("h"); pe.put("at", 0);
                if (blank(pe.path("text").asText(null))) pe.put("text", text(written, "picture", sentences.get(0)));
                return;
            }
        }
        ObjectNode pic = mapper.createObjectNode();
        pic.put("id", "scene-picture");
        pic.put("type", "photo");
        pic.put("style", "full");
        pic.put("text", text(written, "picture", text(written, "title", sentences.get(0))));
        pic.put("at", 0);
        pic.put("dimAt", -1);
        els.insert(0, pic);
    }

    /** Kids scene art direction per age band (modelled on the reference poster: rich 3D animated-film scenes, a recurring child,
     *  a robot buddy for the younger bands, a maker studio for teens). */
    private String kidsScenePrompt(ConceptExplainerJob job, String what, boolean hasReference) {
        int b = ageBand(job);
        String world = b == 1
                ? "a warm cozy children's playroom, soft morning sunlight, plush toys, colourful stacking rings and wooden blocks, a rounded white robot buddy with a glowing blue smiling face; very clean uncluttered composition, the main object large and clear, soft saturated colours"
                : b == 3
                ? "a modern teen maker studio, laptops, soft holographic interface panels, sketch notebooks, cool blue and purple ambient light with a warm desk lamp; confident expressions, clean modern design, subtle tech glow"
                : "a cozy evening study room with a starry night window, a glowing light bulb, a toy rocket, books and doodles, a friendly white robot buddy with a glowing smiling face; warm lamp light with magical blue night tones, playful details";
        String cast = job.getIllustrationCast() == null || job.getIllustrationCast().isBlank() ? "" : " Recurring characters (exactly this look): " + job.getIllustrationCast() + ".";
        return (hasReference ? "Same characters, same art style and same world as the reference image. " : "")
                + "Scene: " + what + "." + cast + " Setting: " + world + ". High-end 3D animated feature film style, soft global illumination, gentle "
                + "rim light, expressive friendly faces, wholesome and cheerful, child-safe. Keep the top third of the frame calm and empty (soft wall, "
                + "sky or light) for a title; characters in the lower two thirds, faces clearly visible. ABSOLUTELY NO TEXT: no letters, words, "
                + "numbers, labels, signs, logos or watermarks anywhere.";
    }

    /** Lesson illustration in the subject's own visual language (medical = clean educational anatomy art). */
    private String illustrationPrompt(ConceptExplainerJob job, String what) {
        String look = healthDomain(job.getDomain())
                ? "clean educational medical illustration, anatomically accurate, soft clinical lighting, calm teal and coral palette, plain light background, textbook quality"
                : "clean educational illustration, clear subject, soft studio lighting, calm palette, plain light background";
        return what + ". " + look + ". Centred subject, nothing cut off. ABSOLUTELY NO TEXT: no letters, labels, numbers, arrows or captions "
                + "(labels are added separately), no watermark, no logo.";
    }

    private static final java.util.Set<String> SCAFFOLD = java.util.Set.of("arrow", "label", "pill", "heading", "journey", "titlecard", "outro",
            "particles", "cables", "icon", "container");

    /** Note the approach and how many different main visuals the lesson really used (low variety = a note, not a failure). */
    private void reportVariety(ConceptExplainerJob job, String approach, int wanted) {
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (ConceptExplainerJob.Scene sc : job.getScenes()) {
            try {
                JsonNode plan = mapper.readTree(sc.getPlanJson());
                for (JsonNode e : plan.path("elements")) {
                    String t = e.path("type").asText();
                    if (!SCAFFOLD.contains(t)) used.add(t);
                }
                if (plan.path("manim").isObject()) used.add("manim:" + plan.path("manim").path("template").asText());
            } catch (Exception ignored) { }
        }
        if (approach != null) job.getWarnings().add("Teaching approach: " + approach.substring(0, approach.indexOf(':') > 0 ? approach.indexOf(':') : approach.length()).toLowerCase(Locale.ROOT)
                + " · visuals used: " + String.join(", ", used));
        if (used.size() < wanted)
            job.getWarnings().add("Only " + used.size() + " kinds of visuals were used (aim " + wanted + "+). A larger model follows the variety rules better - or regenerate for a different approach.");
    }

    private ConceptExplainerJob.Scene bookend(ObjectNode plan, String line, String title) {
        ConceptExplainerJob.Scene s = new ConceptExplainerJob.Scene();
        s.setTemplate("diagram");
        s.setTitle(title);
        s.setSentences(new ArrayList<>(List.of(line)));
        s.setNarration(line);
        s.setVoiceDirection("confident", "normal", "calm explainer");
        s.setPlanJson(plan.toString());
        return s;
    }

    // =================================================================== slide

    private boolean needsIllustration(ConceptExplainerJob job, ConceptExplainerJob.Scene s) {
        if (tutorial(job)) return false;   // tutorial style is pure diagrams, like the reference video
        // Every scene gets a purpose-built visual asset; the selected style changes its art direction.
        return (s.getVisualDescription() != null && !s.getVisualDescription().isBlank())
                || (s.getIllustrationPrompt() != null && !s.getIllustrationPrompt().isBlank());
    }

    /** Generate scene-specific teaching artwork for the selected visual style; text is overlaid separately. */
    private void makeIllustration(ConceptExplainerJob job, ConceptExplainerJob.Scene s) {
        // the object is drawn in the lesson's visual style (neon / sketch / 3D clay / chalk / blueprint / anime)
        String look = ConceptSlideRenderer.style(job.getStyle()).illustrationStyle();
        String subjectBrief = blank(s.getIllustrationPrompt()) ? s.getVisualDescription() : s.getIllustrationPrompt();
        String visualBrief = blank(s.getVisualDescription()) ? subjectBrief : s.getVisualDescription();
        String ratio = vertical(job) ? "portrait 9:16 composition" : "landscape 16:9 composition";
        String prompt = "Create a premium, richly detailed scene-specific illustration for an educational or storytelling video in " + ratio + ". Subject and action: "
                + subjectBrief + ". Scene context: " + visualBrief + ". Spoken narration context: " + safePromptText(s.getNarration(), 700)
                + ". Art direction: " + look + ". "
                + "Use a deliberate cinematic composition with a readable primary focal subject, purposeful supporting details, foreground/midground/background depth, "
                + "material texture, directional motivated lighting, realistic contact shadows, atmospheric perspective where appropriate, and a clear eye path. "
                + "Show a specific action, state, relationship, or change that helps explain this exact scene; avoid a generic symbolic icon or unrelated decoration. "
                + "Respect the subject: scientific diagrams must be structurally accurate; code/software visuals must resemble plausible interfaces without legible generated text; children's scenes must be warm, expressive and age-appropriate; historical/everyday scenes must feel grounded. "
                + "Keep an intentional clean negative-space region for separately rendered captions and labels, with the main subject away from the text-safe area. "
                + "No text, letters, numbers, words, watermark, logo, UI text, border or decorative frame.";
        String negative = "text, letters, words, numbers, typography, watermark, logo, generic app icon, tiny icon, empty composition, "
                + "unrelated objects, random symbols, clutter, blurry, low contrast, decorative frame, border, collage, grid, split panels";
        try {
            int width = vertical(job) ? 768 : 1152;
            int height = vertical(job) ? 1344 : 864;
            var result = gateway.generateImage(new ImageGenerationProvider.ImageGenerationRequest(
                    prompt, negative, width, height, 30, 4.0, null, "qwen-image-2-1-16gb-t2i", null, null, null));
            Path p = storage.store(dir(job) + "/illustrations/scene-" + num(s) + "-v" + (s.getImageVersion() + 1) + ".png", result.imageBytes());
            s.setIllustrationPath(p.toString());
        } catch (Exception e) {
            job.getWarnings().add("Scene " + s.getSceneNumber() + ": illustration failed (" + rootMessage(e) + "), drew a simple icon instead.");
            s.setIllustrationPath(null);
        }
    }

    /** Select a preferred visual engine and separately record the renderer that actually exports the scene.
     * This prevents preview availability from being misreported as production export support. */
    private void assignVisualRoutes(ConceptExplainerJob job) {
        var routes = com.aistorystudio.conceptexplainer.tutorial.VisualRendererRouter.planJob(job, visualCapabilities());
        for (int i = 0; i < job.getScenes().size(); i++) applyRoute(job.getScenes().get(i), routes.get(i));
        long special = job.getScenes().stream().filter(s -> List.of("THREE_JS", "RIVE", "MOTION_CANVAS", "MANIM").contains(s.getVisualEngine())).count();
        if (special > 0 && job.getWarnings().stream().noneMatch(w -> w.startsWith("Visual routing planned per scene."))) job.getWarnings().add("Visual routing planned per scene. Each scene gets the engine that fits its topic (native diagrams, Manim, Three.js, Rive); a failed export falls back to the native renderer and says so.");
    }

    /** What the renderer service can really export now, combined with the Manim sidecar. */
    private com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities visualCapabilities() {
        var rc = new com.aistorystudio.conceptexplainer.tutorial.VisualEngineRenderClient(visualRendererBaseUrl).capabilities();
        return new com.aistorystudio.conceptexplainer.tutorial.EngineScorer.Capabilities(manimAvailable(), rc.rive(), rc.threeJs(), rc.motionCanvas());
    }

    /** The scene's route comes from the lesson-wide plan, so editing or re-rendering one scene keeps the lesson's variety rules. */
    private void applyVisualRoute(ConceptExplainerJob job, ConceptExplainerJob.Scene scene) {
        int idx = job.getScenes().indexOf(scene);
        var routes = com.aistorystudio.conceptexplainer.tutorial.VisualRendererRouter.planJob(job, visualCapabilities());
        var route = idx >= 0 && idx < routes.size() ? routes.get(idx)
                : com.aistorystudio.conceptexplainer.tutorial.VisualRendererRouter.route(job, scene, visualCapabilities());
        applyRoute(scene, route);
    }

    private void applyRoute(ConceptExplainerJob.Scene scene, com.aistorystudio.conceptexplainer.tutorial.VisualRendererRouter.Route route) {
        scene.setVisualEngine(route.engine());
        scene.setVisualEngineReason(route.reason());
        scene.setVisualKind(route.sceneKind());
        if ("NATIVE_DIAGRAM".equals(route.engine())) {
            scene.setActualRenderer("NATIVE_DIAGRAM");
            scene.setRendererStatus("RENDERED");
            scene.setRendererMessage("Native synchronized diagram renderer (chosen on purpose for this scene).");
        } else {
            scene.setActualRenderer("PENDING");
            scene.setRendererStatus("PLANNED");
            scene.setRendererMessage("Planned " + route.engine() + "; awaiting server-side production export.");
        }
    }

    private boolean tryRenderWithVisualEngine(ConceptExplainerJob job, ConceptExplainerJob.Scene s) throws IOException {
        String engine = s.getVisualEngine();
        if (!List.of("MOTION_CANVAS", "THREE_JS", "RIVE").contains(engine)) return false;
        Path riveAsset = null;
        if ("RIVE".equals(engine)) {
            try {
                JsonNode p = mapper.readTree(s.getPlanJson() == null ? "{}" : s.getPlanJson());
                String asset = text(p, "riveAsset", job.getRiveAssetPath());
                // only a file uploaded through /rive-assets: never an absolute path or anything the model wrote into the plan
                if (!blank(asset) && asset.replace('\\', '/').matches("rive-assets/[A-Za-z0-9-]+\\.riv")) {
                    Path candidate = storage.resolve(asset.replace('\\', '/'));
                    if (Files.isRegularFile(candidate)) riveAsset = candidate;
                }
            } catch (Exception ignored) { }
        }
        try {
            int width = vertical(job) ? 1080 : 1920, height = vertical(job) ? 1920 : 1080;
            var client = new com.aistorystudio.conceptexplainer.tutorial.VisualEngineRenderClient(visualRendererBaseUrl);
            List<byte[]> rendered = client.render(engine, s.getTitle(), s.getNarration(), s.getPlanJson(),
                    Math.max(2, Math.min(8, s.getSentences().size())), width, height, riveAsset, s.getVisualKind());
            List<BufferedImage> frames = new ArrayList<>();
            for (byte[] bytes : rendered) {
                BufferedImage frame = ImageIO.read(new ByteArrayInputStream(bytes));
                if (frame == null) throw new IOException("Renderer returned an invalid PNG frame.");
                frames.add(frame);
            }
            writeSteps(job, s, frames);
            if (!s.getStepPaths().isEmpty()) s.setPreviewPath(s.getStepPaths().get(s.getStepPaths().size() - 1));
            s.setActualRenderer(engine);
            s.setRendererStatus("RENDERED");
            s.setRendererMessage(engine + " server-side export rendered " + frames.size() + " progressive frames.");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.setActualRenderer("NATIVE_DIAGRAM"); s.setRendererStatus("FALLBACK");
            s.setRendererMessage("Planned " + engine + "; export was interrupted, so native synchronized diagrams were used.");
            job.getWarnings().add("Scene " + s.getSceneNumber() + ": " + engine + " export interrupted; native diagram fallback used.");
            return false;
        } catch (Exception e) {
            s.setActualRenderer("NATIVE_DIAGRAM"); s.setRendererStatus("FALLBACK");
            s.setRendererMessage("Planned " + engine + "; export failed (" + rootMessage(e) + "). Native synchronized diagrams were used.");
            job.getWarnings().add("Scene " + s.getSceneNumber() + ": " + engine + " export failed (" + rootMessage(e) + "); native diagram fallback used.");
            log.warn("Visual engine export failed for scene {} engine {}: {}", s.getSceneNumber(), engine, rootMessage(e));
            return false;
        }
    }

    private void renderSlide(ConceptExplainerJob job, ConceptExplainerJob.Scene s) throws IOException {
        applyVisualRoute(job, s);
        if (tryRenderWithVisualEngine(job, s)) {
            s.setIntroPattern(null);
            s.setTypeOverlays(new ArrayList<>());
            return;
        }
        if (tutorial(job)) {
            // one frame per narration sentence: frame k = everything that has appeared by sentence k
            DiagramRenderer.Scene scene = DiagramSpec.toScene(mapper.readTree(s.getPlanJson()), s.getSentences().size());
            String still = manimStill(job, s);
            if (still != null) {
                scene = DiagramRenderer.withBackdrop(scene, still);
                s.setAnimationPath(null);   // a fresh animation is rendered after the narration
            }
            String backdrop = (kids(job) || still != null) ? DiagramRenderer.backdropPath(scene) : null;
            s.setBackdropPath(backdrop);
            if (backdrop != null) {
                // illustrated scene (like the Story flow): the picture is animated underneath with a slow camera move,
                // the overlays (titles, bubbles, quiz cards) are transparent frames revealed sentence by sentence on top
                writeSteps(job, s, diagrams.renderOverlays(scene, theme(job), vertical(job)));
                List<BufferedImage> full = diagrams.render(scene, theme(job), vertical(job));   // picture + overlays, for the storyboard
                Path prev = storage.resolve(dir(job) + "/slides/scene-" + num(s) + "-v" + s.getImageVersion() + "-preview.png");
                ImageIO.write(full.get(full.size() - 1), "png", prev.toFile());
                s.setPreviewPath(prev.toString());
                s.setIntroPattern(null);
                s.setTypeOverlays(new ArrayList<>());
                return;
            }
            List<BufferedImage> frames = diagrams.render(scene, theme(job), vertical(job));
            writeSteps(job, s, frames);
            // opening motion from the previous scene: kept elements glide, the camera pulls back for overviews,
            // old elements fade out, new ones fade in (played over the first INTRO_FRAMES of the clip)
            s.setIntroPattern(null);
            s.setTypeOverlays(new ArrayList<>());
            if (!"STATIC".equals(job.getMotion())) {
                int idx = job.getScenes().indexOf(s);
                ConceptExplainerJob.Scene before = idx > 0 ? job.getScenes().get(idx - 1) : null;
                DiagramRenderer.Scene prev = before == null ? null
                        : DiagramSpec.toScene(mapper.readTree(before.getPlanJson()), before.getSentences().size());
                List<BufferedImage> intro = diagrams.renderIntro(prev, scene, theme(job), INTRO_FRAMES, vertical(job));
                if (!intro.isEmpty()) {
                    String pattern = dir(job) + "/slides/scene-" + num(s) + "-v" + s.getImageVersion() + "-intro-%02d.png";
                    for (int k = 0; k < intro.size(); k++) {
                        Path p = storage.resolve(String.format(Locale.ROOT, pattern, k));
                        Files.createDirectories(p.getParent());
                        ImageIO.write(intro.get(k), "png", p.toFile());
                    }
                    s.setIntroPattern(storage.resolve(pattern).toString());
                }
                // typewriter reveals for callouts that appear later in the scene (breakdown look)
                List<String> typed = new ArrayList<>();
                for (int k = 1; k < s.getSentences().size(); k++) {
                    List<BufferedImage> fr = diagrams.renderTyping(scene, k, theme(job), vertical(job), 30);
                    if (fr.isEmpty()) continue;
                    String tp = dir(job) + "/slides/scene-" + num(s) + "-v" + s.getImageVersion() + "-type" + k + "-%02d.png";
                    for (int i = 0; i < fr.size(); i++) {
                        Path p = storage.resolve(String.format(Locale.ROOT, tp, i));
                        Files.createDirectories(p.getParent());
                        ImageIO.write(fr.get(i), "png", p.toFile());
                    }
                    typed.add(k + "|" + storage.resolve(tp));
                }
                s.setTypeOverlays(typed);
            }
            return;
        }
        BufferedImage ill = null;
        if (s.getIllustrationPath() != null) {
            try { ill = ImageIO.read(Path.of(s.getIllustrationPath()).toFile()); } catch (Exception ignored) { }
        }
        ConceptSlideRenderer.Slide slide = toSlide(mapper.readTree(s.getPlanJson()), s, ill);
        // style chosen by the user; the lesson id seeds colour rotation + mirrored/alternative layouts
        List<BufferedImage> steps = renderer.render(slide, job.getStyle(), job.getId().getMostSignificantBits());
        writeSteps(job, s, steps);
    }

    private void writeSteps(ConceptExplainerJob job, ConceptExplainerJob.Scene s, List<BufferedImage> steps) throws IOException {
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
        List<Double> starts = new ArrayList<>(), lengths = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < s.getSentences().size(); i++) {
            String line = s.getSentences().get(i).trim();
            boolean question = line.endsWith("?") || line.endsWith("？");
            boolean excited = line.endsWith("!") || line.contains("[laugh]") || line.contains("[chuckle]");
            double speed = question ? 0.96 : (excited ? 0.99 : (i % 3 == 1 ? 0.97 : 1.0));
            String v = job.getVoice() == null ? "" : job.getVoice();
            boolean tut = tutorial(job);
            if (tut) speed = question ? 0.98 : 1.0;          // reference: steady ~160 wpm, no theatrical slow-downs
            if (isBreakdown(job)) speed = question ? 1.02 : 1.07;   // breakdown reference: brisk ~175 wpm
            if (kids(job)) speed = ageBand(job) == 1 ? (question ? 0.82 : 0.86)    // 2-5: slow, warm, clear
                               : ageBand(job) == 3 ? 1.0                            // 10-15: normal conversational pace
                               : (question ? 0.9 : 0.93);                            // 5-10: unhurried
            if (v.startsWith("edge:")) speed *= 0.94;          // Edge is brisk; a calmer teacher pace sounds more human
            else if (v.startsWith("indic:")) speed *= 0.98;    // IndicF5 is already natural; tiny breath room
            else if (v.startsWith("speak:")) speed *= 1.0;     // Indic-Speak is paced for explanation already (unhurried by design)
            double pitch = question ? 1.015 : (excited ? 1.01 : (i % 4 == 0 ? 0.99 : 1.0));
            if (tut) pitch = question ? 1.01 : 1.0;
            // a person does not read every line at one speed: short lines land slower, long ones flow, the last line settles
            var pacing = com.aistorystudio.conceptexplainer.speech.SpeechPacing.plan(line, i, s.getSentences().size()).scaled(pacingFactor(job));
            speed *= pacing.speedMul();
            pitch *= pacing.pitchMul();
            VoiceDirection vdir = VoiceDirection.of(s.getVoiceEmotion(), s.getVoicePace(), s.getVoiceDelivery(),
                    TeachingStyle.parse(job.getTeachingStyle()).baseEmotion());
            VoiceDirection.TtsParams tp = vdir.toTts(job.getVoice(), speed, pitch, question, excited);
            if (!tp.engineUnderstandsEmotion() && job.getWarnings().stream().noneMatch(w -> w.startsWith("Voice style:"))) {
                job.getWarnings().add("Voice style: this voice has no emotion model, so the teaching tone is applied through pace and pitch only. "
                        + "Pick a Chatterbox or cloned voice for fuller expression.");
            }
            if (i == 0 && job.getWarnings().stream().noneMatch(w -> w.startsWith("Voice used:"))) job.getWarnings().add("Voice used: " + describeVoice(job));
            // captions keep the written line; the voice gets the spoken form ("count++" -> "count plus plus", "O(n)" -> "big O of n")
            String spoken = com.aistorystudio.conceptexplainer.speech.SpokenText.forSpeech(line, job.getLanguage());
            if (spoken.isBlank()) spoken = line;
            var audio = gateway.synthesizeForStoryLanguage(new TextToSpeechProvider.TtsRequest(
                    spoken, job.getVoice(), job.getLanguage(), tp.speed(), tp.pitch(),
                    tp.emotion(), tp.intensity(), kids(job) ? (ageBand(job) == 1
                            ? "warm, gentle children's storyteller, smiling voice, slow and clear, soft wonder, like reading a picture book to a toddler"
                            : ageBand(job) == 3
                            ? "friendly, energetic young creator talking to a capable teen, curious and real, never childish"
                            : "warm, playful children's storyteller, smiling voice, gentle excitement, expressive character voices for dialogue")
                            : tut ? "calm, confident senior instructor recording a course, conversational, steady pace, no drama"
                            : "natural conversational tutor, " + vdir.delivery(), emphasisFor(s, line), true,
                    (line.contains("[laugh]") || line.contains("[chuckle]")) ? "chuckle" : null, tp.acting(), null));
            if (audio.providerWarning() != null && job.getWarnings().stream().noneMatch(w -> w.startsWith("Voice:"))) {
                job.getWarnings().add("Voice: " + audio.providerWarning());
            }
            Path p = work.resolve("s" + i + ".wav");
            Files.write(p, audio.audioBytes());
            parts.add(p);
            starts.add(t);
            double d = media.probeDurationSeconds(p);
            if (d <= 0) d = audio.durationSeconds();
            lengths.add(d);
            double gap = i < s.getSentences().size() - 1 ? gapAt(job, s, i) : 0.0;
            t += d + gap;
        }
        // join sentences with short breaths, then the shared voice polish (length unchanged)
        Path joined = work.resolve("joined.wav");
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        for (Path p : parts) args.addAll(List.of("-i", p.toString()));
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            g.append('[').append(i).append(":a]aresample=48000,aformat=channel_layouts=stereo")
                    .append(i < parts.size() - 1 ? ",apad=pad_dur=" + fmt(gapAt(job, s, i)) : "").append("[s").append(i).append("];");
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
        s.setDurationSeconds((speech > 0 ? speech : t) + tail(job));
        s.setSentenceTiming(starts, lengths);
        s.setStepTimes(stepTimes(starts, s.getStepPaths().size(), speech > 0 ? speech : t, job));
    }

    private double gap(ConceptExplainerJob job, String sentence) {
        if (!tutorial(job)) return sentenceGap(sentence);
        if (kids(job)) {   // pseudo-interaction: after a question to the child, leave time to answer out loud
            String k = sentence == null ? "" : sentence.trim();
            int b = ageBand(job);
            if (b == 1) return k.endsWith("?") ? 2.5 : 0.8;   // toddlers: long, patient pauses
            if (b == 3) return k.endsWith("?") ? 0.7 : 0.32;  // teens: rhetorical questions, normal pace
            return k.endsWith("?") ? 1.4 : 0.5;
        }
        if (vertical(job)) {   // reels: tighter (measured 0.20-0.36 s between sentences)
            String y = sentence == null ? "" : sentence.trim();
            return y.endsWith("?") ? 0.38 : 0.28;
        }
        // measured on the reference narration: median pause 0.34 s, questions a little longer
        String x = sentence == null ? "" : sentence.trim();
        if (x.endsWith("?")) return 0.42;
        if (x.endsWith(":")) return 0.40;
        return 0.34;
    }

    private double tail(ConceptExplainerJob job) { return vertical(job) ? 0.3 : tutorial(job) ? TUTORIAL_TAIL : SCENE_TAIL; }

    /** 60-second vertical short: reel look (both reference reels), 1080x1920, word captions, title + outro cards. */
    static boolean isShort(ConceptExplainerJob job) { return job != null && job.isShort(); }

    static boolean isBreakdown(ConceptExplainerJob job) { return job != null && job.isBreakdown(); }

    /** Vertical 1080x1920 output (60-second short, real-world breakdown). */
    static boolean vertical(ConceptExplainerJob job) { return isShort(job) || isBreakdown(job); }

    /** 0 = not a kids lesson, 1 = ages 2-5, 2 = ages 5-10, 3 = ages 10-15. */
    static int ageBand(ConceptExplainerJob job) {
        String t = job == null ? null : job.getTrack();
        if ("KIDS_2_5".equals(t)) return 1;
        if ("KIDS".equals(t)) return 2;
        if ("KIDS_10_15".equals(t)) return 3;
        return 0;
    }

    static boolean kids(ConceptExplainerJob job) { return ageBand(job) > 0; }

    /** Cross-fade length: calm and slow for the youngest (fast cutting taxes their attention), brisk for teens. */
    private static double fade(ConceptExplainerJob job) {
        int b = ageBand(job);
        double base = b == 1 ? 0.8 : b == 2 ? 0.5 : FADE;
        String m = job.getMotionIntensity();          // creative dial: calm = longer, softer fades; high energy = quicker changes
        return "LOW".equals(m) ? base + 0.2 : "HIGH".equals(m) ? Math.max(0.25, base - 0.1) : base;
    }

    private String theme(ConceptExplainerJob job) {
        if (kids(job)) return ageBand(job) == 1 ? "toddler" : ageBand(job) == 3 ? "teen" : "kids";
        if (isShort(job)) return "reel";
        if (isBreakdown(job)) return "blueprint3d";
        // the default tech look on a health / biology lesson is the wrong world: switch to the clinical look automatically
        if ("reference".equals(job.getStyle()) && healthDomain(job.getDomain())) return "clinical";
        return job.getStyle();
    }

    static boolean healthDomain(String d) {
        String x = d == null ? "" : d.toLowerCase(Locale.ROOT);
        return x.contains("medic") || x.contains("health") || x.contains("biolog") || x.contains("anatom") || x.contains("body");
    }

    /** Subject area from the planner, with a keyword safety net for when the model leaves it out. */
    static String detectDomain(String fromModel, String topic) {
        if (fromModel != null && !fromModel.isBlank() && !fromModel.equalsIgnoreCase("other")) return fromModel.toLowerCase(Locale.ROOT);
        String t = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        String[] med = {"ear", "eye", "heart", "blood", "lung", "breath", "brain", "tooth", "teeth", "bone", "skin", "fever", "virus", "bacteria",
                "vaccine", "immune", "disease", "doctor", "medicine", "diabetes", "sugar level", "cancer", "pain", "sleep", "digest", "stomach",
                "kidney", "liver", "muscle", "allergy", "infection", "pregnan", "hormone", "body", "health", "cell", "dna", "gene"};
        for (String k : med) if (t.matches(".*\\b" + k + ".*")) return "medical";
        String[] fin = {"money", "loan", "interest", "tax", "invest", "stock", "mutual fund", "emi", "budget", "salary", "inflation", "bank"};
        for (String k : fin) if (t.contains(k)) return "finance";
        return "technology";
    }

    /** Reference-video teaching + diagram engine, for every visual style unless the user picked the classic slides. */
    /** Plain-language name of the narrator, so a US / Indian / native voice choice is visible on the lesson. */
    static String describeVoice(ConceptExplainerJob job) {
        String v = job.getVoice() == null ? "" : job.getVoice();
        boolean english = TutorialPrompts.english(job.getLanguage()) || String.valueOf(job.getLanguage()).toLowerCase(Locale.ROOT).contains("indian english");
        if (v.startsWith("narrator-in-")) return (v.endsWith("female") ? "Meera" : "Arjun") + " - Indian English (Chatterbox clone of the Edge en-IN voice)"
                + (english ? "" : "; " + job.getLanguage() + " narration uses the native " + job.getLanguage() + " voice instead");
        if (v.equals("narrator-male") || v.equals("narrator-female")) return (v.endsWith("female") ? "Amy" : "Ryan") + " - US English (Chatterbox)"
                + (english ? ". For an Indian accent pick Arjun / Meera - Indian English." : "; " + job.getLanguage() + " narration uses the native voice instead");
        if (v.startsWith("edge:")) return v.substring(5) + " (Edge neural, cloud)";
        if (v.startsWith("speak:")) return v.substring(6) + " (Indic-Speak, local)";
        if (v.startsWith("indic:")) return v.substring(6) + " (IndicF5, local)";
        return v.isEmpty() ? "default" : v;
    }

    static boolean tutorial(ConceptExplainerJob job) { return job != null && !"CLASSIC".equals(job.getFormat()); }

    private static double sentenceGap(String sentence) {
        if (sentence == null) return SENTENCE_GAP;
        String x = sentence.toLowerCase(Locale.ROOT);
        if (x.contains("[chuckle]") || x.contains("[laugh]")) return 0.42;
        if (x.endsWith("?") || x.endsWith("？")) return 0.38;
        if (x.endsWith("!") || x.endsWith("！")) return 0.34;
        if (x.contains("...") || x.contains("…")) return 0.44;
        return SENTENCE_GAP;
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
            double per = Math.max(0.5, (s.getDurationSeconds() - tail(job)) / Math.max(1, s.getSentences().size()));
            for (int i = 0; i < s.getSentences().size(); i++) starts.add(i * per);
            s.setStepTimes(stepTimes(starts, s.getStepPaths().size(), s.getDurationSeconds() - tail(job), job));
        }
        double total = s.getDurationSeconds();
        List<String> steps = s.getStepPaths();
        List<Double> t = s.getStepTimes();
        int n = "STATIC".equals(job.getMotion()) ? 1 : steps.size();
        String backdrop = s.getBackdropPath() != null && Files.isRegularFile(Path.of(s.getBackdropPath())) ? s.getBackdropPath() : null;
        String pix = backdrop != null ? "yuva420p" : "yuv420p";   // overlay frames keep their transparency over the picture
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        StringBuilder f = new StringBuilder();
        if (n == 1) {
            args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(total), "-i", steps.get(steps.size() - 1)));
            f.append("[0:v]fps=30,format=").append(pix).append(",setsar=1[v0]");
        } else {
            for (int k = 0; k < n; k++) {
                double end = k + 1 < n ? t.get(k + 1) : total;
                double len = (end - t.get(k)) + (k == 0 ? 0 : fade(job));
                if (k == 0) len = t.get(1);
                if (k == n - 1 && k > 0) len = total - t.get(k) + fade(job);
                args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(len), "-i", steps.get(k)));
                f.append('[').append(k).append(":v]fps=30,format=").append(pix).append(",setsar=1[i").append(k).append("];");
            }
            String prev = "i0";
            for (int k = 1; k < n; k++) {
                String out = k == n - 1 ? "v0" : "x" + k;
                f.append('[').append(prev).append("][i").append(k).append("]xfade=transition=fade:duration=").append(fmt(fade(job)))
                        .append(":offset=").append(fmt(t.get(k) - fade(job))).append('[').append(out).append("];");
                prev = out;
            }
            f.setLength(f.length() - 1);
        }
        // Overlays on the reveal timeline: the scene-opening motion, then typewriter reveals at their steps.
        boolean moving = !"STATIC".equals(job.getMotion());
        String cur = "v0";
        int input = n;
        int vw0 = vertical(job) ? 1080 : 1920, vh0 = vertical(job) ? 1920 : 1080;
        String anim = s.getAnimationPath() != null && Files.isRegularFile(Path.of(s.getAnimationPath())) ? s.getAnimationPath() : null;
        if (backdrop != null && anim != null) {
            // animated cartoon: the Wan clip of this picture, held on its last frame if the narration is longer
            args.addAll(List.of("-i", anim));
            f.append(";[").append(input).append(":v]scale=").append(vw0).append(':').append(vh0)
                    .append(":force_original_aspect_ratio=increase:flags=lanczos,crop=").append(vw0).append(':').append(vh0)
                    .append(",fps=30,setsar=1,tpad=stop_mode=clone:stop_duration=").append(fmt(total)).append(",format=yuv420p[bg]")
                    .append(";[bg][").append(cur).append("]overlay=format=auto,format=yuv420p[vb]");
            cur = "vb";
            input++;
        } else if (backdrop != null) {
            args.addAll(List.of("-loop", "1", "-framerate", "30", "-t", fmt(total), "-i", backdrop));
            f.append(';').append(kenBurns(input, vw0, vh0, total, s.getSceneNumber(), ageBand(job), moving, cameraMove(s.getCamera()), motionFactor(job)))
                    .append(";[bg][").append(cur).append("]overlay=format=auto,format=yuv420p[vb]");
            cur = "vb";
            input++;
        }
        if (s.getIntroPattern() != null && moving) {
            args.addAll(List.of("-framerate", "30", "-start_number", "0", "-i", s.getIntroPattern()));
            f.append(";[").append(input).append(":v]fps=30,format=yuv420p,setsar=1[intro];[").append(cur).append("][intro]overlay=eof_action=pass:format=auto[vi]");
            cur = "vi";
            input++;
        }
        if (moving && n > 1) {
            int o = 0;
            for (String entry : s.getTypeOverlays()) {
                int bar = entry.indexOf('|');
                if (bar <= 0) continue;
                int k = Integer.parseInt(entry.substring(0, bar));
                if (k <= 0 || k >= t.size()) continue;
                double at = Math.max(0, t.get(k) - fade(job));   // cover the cross-fade with the typing frames
                args.addAll(List.of("-framerate", "30", "-start_number", "0", "-i", entry.substring(bar + 1)));
                String lbl = "vt" + o;
                f.append(";[").append(input).append(":v]fps=30,format=yuv420p,setsar=1,setpts=PTS+").append(fmt(at)).append("/TB[ty").append(o)
                        .append("];[").append(cur).append("][ty").append(o).append("]overlay=eof_action=pass:format=auto[").append(lbl).append(']');
                cur = lbl;
                input++;
                o++;
            }
        }
        // Camera: real-world breakdowns get a stronger dolly-in; tutorial scenes get a restrained 1.2% push-in
        // so the frame feels alive without making code/labels hard to read. Applied after overlays to prevent layers
        // drifting apart. STATIC explicitly disables all camera movement.
        double zoom = !moving ? 0 : isBreakdown(job) ? 0.045 * motionFactor(job)
                : tutorial(job) ? 0.012 * motionFactor(job) : subtleZoom * motionFactor(job);
        int vw = vertical(job) ? 1080 : 1920, vh = vertical(job) ? 1920 : 1080;
        if (zoom > 0) {
            f.append(";[").append(cur).append("]scale=w='2*trunc(").append(vw).append("*(1+").append(fmt(zoom)).append("*t/").append(fmt(Math.max(1, total)))
                    .append(")/2)':h=-2:eval=frame:flags=bicubic,crop=").append(vw).append(':').append(vh).append(",setsar=1,format=yuv420p[v]");
        } else {
            f.append(";[").append(cur).append("]null[v]");
        }
        String finalLabel = "[v]";
        if (backdrop != null && kids(job)) {   // kids: each scene opens with a soft dip from white, like a page turn in a picture book (never on a dark maths scene)
            f.append(";[v]fade=t=in:st=0:d=").append(fmt(ageBand(job) == 1 ? 0.6 : 0.4)).append(":color=white[vf]");
            finalLabel = "[vf]";
        }
        int audioIndex = input;
        args.addAll(List.of("-i", s.getAudioPath()));
        f.append(";[").append(audioIndex).append(":a]aresample=48000,aformat=channel_layouts=stereo,apad[a]");
        Path out = storage.resolve(dir(job) + "/clips/scene-" + num(s) + "-i" + s.getImageVersion() + "-a" + s.getAudioVersion() + ".mp4");
        Files.createDirectories(out.getParent());
        String videoLabel = finalLabel;
        List<String> captionText = s.getCaptions().size() == s.getSentences().size() ? s.getCaptions()
                : TutorialPrompts.english(job.getLanguage()) ? s.getSentences() : List.of();
        if (isShort(job) && !s.getSentenceStarts().isEmpty() && !captionText.isEmpty()) {
            // word-by-word captions (spoken words white, the rest of the chunk grey) burned in with libass
            Path ass = storage.resolve(dir(job) + "/clips/scene-" + num(s) + "-a" + s.getAudioVersion() + ".ass");
            Files.createDirectories(ass.getParent());
            Files.writeString(ass, ShortCaptions.ass(captionText, s.getSentenceStarts(), s.getSentenceLengths(), 1080, 1920, ShortCaptions.FONT), StandardCharsets.UTF_8);
            String fontDir = captionFonts();
            filterInsertCaptions(f, videoLabel, ass, fontDir);
            videoLabel = "[vc]";
        }
        args.addAll(List.of("-filter_complex", f.toString(), "-map", videoLabel, "-map", "[a]", "-t", fmt(total),
                "-c:v", "libx264", "-preset", "medium"));
        // illustrated scenes keep the film tune; flat slides get NO tune plus adaptive quantisation 3, which (measured) leaves no flat bands
        // in the soft backgrounds after two encodes, where the old stillimage tune smoothed the dither away
        if (backdrop != null) args.addAll(List.of("-tune", "film"));
        args.addAll(List.of("-aq-mode", "3", "-crf", "17", "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", out.toString()));
        ffmpeg(args);
        s.setClipPath(out.toString());
    }

    /**
     * Slow camera across a still illustration (the Story-flow look, no image-to-video): push-in, pan right, pull-out, pan
     * left or a gentle rise, chosen by scene number so consecutive scenes move differently. Eased (smoothstep). Ages 2-5
     * get half the movement (calm), teens a little more.
     */
    static String kenBurns(int input, int vw, int vh, double total, int sceneNumber, int band, boolean moving) {
        return kenBurns(input, vw, vh, total, sceneNumber, band, moving, -1, 1.0);
    }

    /** Camera idea written by the story planner: 0 push-in, 1 pan-right, 2 pull-out, 3 pan-left, 4 rise, -2 hold, -1 = by scene number. */
    static int cameraMove(String camera) {
        String c = camera == null ? "" : camera.toLowerCase(Locale.ROOT);
        if (c.contains("hold") || c.contains("static")) return -2;
        if (c.contains("push") || c.contains("zoom-in") || c.contains("zoom in")) return 0;
        if (c.contains("pan-right") || c.contains("pan right")) return 1;
        if (c.contains("pull") || c.contains("zoom-out") || c.contains("zoom out")) return 2;
        if (c.contains("pan-left") || c.contains("pan left")) return 3;
        if (c.contains("rise") || c.contains("tilt-up") || c.contains("tilt up")) return 4;
        return -1;
    }

    static double motionFactor(ConceptExplainerJob job) {
        String m = job == null ? "MID" : job.getMotionIntensity();
        return "LOW".equals(m) ? 0.6 : "HIGH".equals(m) ? 1.4 : 1.0;
    }

    static String kenBurns(int input, int vw, int vh, double total, int sceneNumber, int band, boolean moving, int forcedMove, double motionFactor) {
        double amp = (band == 1 ? 0.5 : band == 3 ? 1.2 : 1.0) * motionFactor;
        if (forcedMove == -2) moving = false;
        String T = String.valueOf(Math.max(1.0, total));
        String p = "(t/" + T + ")*(t/" + T + ")*(3-2*(t/" + T + "))";   // smoothstep 0 -> 1
        String base = "[" + input + ":v]scale=" + vw + ":" + vh + ":force_original_aspect_ratio=increase:flags=lanczos,crop=" + vw + ":" + vh + ",setsar=1";
        if (!moving) return base + ",fps=30,format=yuv420p[bg]";
        int move = forcedMove >= 0 ? forcedMove : Math.floorMod(sceneNumber - 1, 5);
        double z0, z1;
        String x, y;
        switch (move) {
            case 0 -> { z0 = 1.02; z1 = 1.02 + 0.10 * amp; x = "(iw-ow)/2"; y = "(ih-oh)/2"; }                 // push in
            case 1 -> { z0 = z1 = 1.06 + 0.08 * amp; x = "(iw-ow)*" + p; y = "(ih-oh)/2"; }                       // pan right
            case 2 -> { z0 = 1.02 + 0.10 * amp; z1 = 1.02; x = "(iw-ow)/2"; y = "(ih-oh)/2"; }                 // pull out
            case 3 -> { z0 = z1 = 1.06 + 0.08 * amp; x = "(iw-ow)*(1-" + p + ")"; y = "(ih-oh)/2"; }              // pan left
            default -> { z0 = z1 = 1.05 + 0.08 * amp; x = "(iw-ow)/2"; y = "(ih-oh)*(1-0.8*" + p + ")"; }        // gentle rise
        }
        String z = z0 == z1 ? String.valueOf(z0) : "(" + z0 + "+(" + (z1 - z0) + ")*" + p + ")";
        return base + ",fps=30,scale=w='2*trunc(" + vw + "*" + z + "/2)':h=-2:eval=frame:flags=bicubic"
                + ",crop=" + vw + ":" + vh + ":x='" + x + "':y='" + y + "',format=yuv420p[bg]";
    }

    private static void filterInsertCaptions(StringBuilder f, String in, Path ass, String fontDir) {
        f.append(';').append(in).append("ass=filename='").append(escFilter(ass.toAbsolutePath().toString())).append('\'');
        if (fontDir != null) f.append(":fontsdir='").append(escFilter(fontDir)).append('\'');
        f.append("[vc]");
    }

    /** ffmpeg filter-graph quoting for paths inside '...'. */
    private static String escFilter(String p) { return p.replace("\\", "/"); }   // storage paths: no quotes or colons

    private volatile String captionFontDir;

    /** Lato + the Indian-script Noto fonts copied out of the jar once, so libass can draw every language. */
    private String captionFonts() {
        if (captionFontDir != null) return captionFontDir;
        try {
            Path d = storage.resolve("concept-explainers/_fonts");
            Files.createDirectories(d);
            for (String f : List.of("Poppins-ExtraBold.ttf", "Poppins-Bold.ttf", "Lato-Bold.ttf", "Lato-Regular.ttf", "NotoSansDevanagari.ttf", "NotoSansKannada.ttf", "NotoSansTamil.ttf",
                    "NotoSansTelugu.ttf", "NotoSansMalayalam.ttf", "NotoSansBengali.ttf", "NotoSansGujarati.ttf", "NotoSansGurmukhi.ttf", "NotoSansOriya.ttf")) {
                Path out = d.resolve(f);
                if (Files.isRegularFile(out)) continue;
                try (var in = ConceptExplainerService.class.getResourceAsStream("/concept-fonts/" + f)) {
                    if (in != null) Files.copy(in, out);
                }
            }
            captionFontDir = d.toAbsolutePath().toString();
        } catch (IOException e) {
            log.warn("Caption fonts not available ({}); libass will use system fonts", e.getMessage());
        }
        return captionFontDir;
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
        if (!tutorial(job) || isShort(job)) {   // lessons + breakdowns: clean voice like their references; shorts: quiet bed like the reel
            try { music = media.musicPresetPath("calm"); } catch (Exception ignored) { }
        }
        Path out = dir.resolve("concept-explainer-" + (job.getVideoVersion() + 1) + ".mp4");
        String fadeOut = fmt(Math.max(0, total - 0.8));
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", joined.toString()));
        String filter;
        if (music != null && Files.isRegularFile(music)) {
            args.addAll(List.of("-stream_loop", "-1", "-i", music.toString()));
            filter = "[0:v]fade=t=in:st=0:d=0.5,fade=t=out:st=" + fadeOut + ":d=0.8[v];"
                    + "[1:a]aresample=48000,aformat=channel_layouts=stereo,volume=" + (isShort(job) ? "0.06" : "0.10") + "[m];"
                    + "[0:a]asplit=2[voice][key];[m][key]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=450[duck];"
                    + "[voice][duck]amix=inputs=2:duration=first:normalize=0,afade=t=out:st=" + fadeOut + ":d=0.8[a]";
        } else {
            filter = "[0:v]fade=t=in:st=0:d=0.5,fade=t=out:st=" + fadeOut + ":d=0.8[v];[0:a]afade=t=out:st=" + fadeOut + ":d=0.8[a]";
        }
        args.addAll(List.of("-filter_complex", filter, "-map", "[v]", "-map", "[a]", "-t", fmt(total),
                "-c:v", "libx264", "-preset", "medium", "-aq-mode", "3", "-crf", "17", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.toString()));
        ffmpeg(args);
        String previous = job.getVideoPath();
        // One volume for every lesson, whichever voice engine spoke it (Piper, Edge, Chatterbox and Indic voices differ a lot):
        // -14 LUFS for social, -16 LUFS for classroom use. Two-pass, linear gain, picture untouched; never fails the lesson.
        try {
            var target = com.aistorystudio.qa.Loudness.of("CLASSROOM".equalsIgnoreCase(job.getDestination()) ? "podcast" : "social");
            Path leveled = out.resolveSibling("ln-" + out.getFileName());
            Double before = com.aistorystudio.qa.Loudness.normalize("ffmpeg", out, leveled, target);
            if (before != null) {
                Files.move(leveled, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                job.getWarnings().removeIf(w -> w.startsWith("Loudness:"));
                job.getWarnings().add(String.format(Locale.ROOT, "Loudness evened out: %.1f LUFS -> %.0f LUFS.", before, target.lufs()));
            } else Files.deleteIfExists(leveled);
        } catch (Exception e) {
            log.warn("Loudness step skipped: {}", e.getMessage());
        }
        job.setVideoPath(out.toString());
        job.setTotalDurationSeconds(media.probeDurationSeconds(out));
        try {   // look at the finished file the way a viewer would: silent gaps, black frames, clipping (never fails the lesson)
            var findings = com.aistorystudio.qa.RenderQa.review("ffmpeg", out, new com.aistorystudio.qa.RenderQa.Options(ageBand(job) == 1 ? 5.5 : 4.5, 0, true));
            job.getWarnings().removeIf(w -> w.startsWith("Quality check"));
            job.getWarnings().add(findings.isEmpty() ? "Quality check passed: no silent gaps, black frames or clipping found."
                    : "Quality check: " + String.join(" ", com.aistorystudio.qa.RenderQa.summarise(findings, 4)));
        } catch (Exception e) {
            log.warn("Quality check skipped: {}", e.getMessage());
        }
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
            // The useful line ("Padded dimensions cannot be smaller...", "No such file...") comes BEFORE
            // the generic "Could not open encoder before EOF" tail, so surface the first real error lines.
            StringBuilder cause = new StringBuilder();
            for (String line : s.split("\\R")) {
                String l = line.toLowerCase(Locale.ROOT);
                if ((l.contains("error") || l.contains("invalid") || l.contains("cannot") || l.contains("no such file")
                        || l.contains("failed") || l.contains("not found")) && !l.contains("could not open encoder before eof")
                        && !l.contains("terminating thread") && !l.contains("task finished")) {
                    if (cause.length() > 0) cause.append(" | ");
                    cause.append(line.trim());
                    if (cause.length() > 600) break;
                }
            }
            String tail = s.length() > 400 ? s.substring(s.length() - 400) : s;
            throw new IllegalStateException("ffmpeg failed: " + (cause.length() > 0 ? cause : tail));
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

    private String safePromptText(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\r\\n]+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

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
