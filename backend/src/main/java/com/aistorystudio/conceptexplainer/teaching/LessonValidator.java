package com.aistorystudio.conceptexplainer.teaching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a lesson plan AND repairs what can be repaired deterministically (opening line, stage
 * directions leaking into narration, unsupported voice values, missing objectives / visual
 * descriptions, subscribe closing). Remaining ERRORs go back to the LLM as a targeted repair prompt.
 * Pure Java: no JSON, no framework.
 */
public final class LessonValidator {

    public enum Level { ERROR, WARN, FIXED }

    public record Issue(Level level, int scene, String code, String message) {
        @Override public String toString() { return (scene > 0 ? "Scene " + scene + ": " : "") + message; }
    }

    public record Context(String topic, String language, Destination destination, TopicKind kind, TeachingStyle style,
                          List<String> outlineSections, List<String> outlineObjectives) { }

    public static final class Report {
        private final List<Issue> issues = new ArrayList<>();
        public List<Issue> issues() { return issues; }
        void add(Level l, int scene, String code, String msg) { issues.add(new Issue(l, scene, code, msg)); }
        public List<Issue> errors() { return issues.stream().filter(i -> i.level() == Level.ERROR).toList(); }
        public boolean hasErrors() { return issues.stream().anyMatch(i -> i.level() == Level.ERROR); }
        public int count(Level l) { return (int) issues.stream().filter(i -> i.level() == l).count(); }
        public boolean has(String code) { return issues.stream().anyMatch(i -> i.code().equals(code)); }
        public List<String> errorMessages() { return errors().stream().map(Issue::toString).toList(); }
    }

    private static final Set<String> CODE_TEMPLATES = Set.of("analogy_code", "code_anatomy", "code_visual", "code_block");
    private static final Set<String> VALID_TEMPLATES = Set.of("hero", "definition", "analogy", "analogy_code", "code_anatomy", "table", "code_visual",
            "code_block", "example_list", "checklist", "summary", "flow", "compare");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "are", "was", "you", "your", "can", "will",
            "has", "have", "its", "into", "when", "what", "how", "not", "but", "all", "one", "out", "get", "use", "any", "than", "then", "they", "them",
            "each", "about", "which", "while", "there", "their", "here", "just", "like", "now", "see", "let", "our", "who", "why");
    private static final Pattern ALLOWED_CUE = Pattern.compile("\\[(chuckle|laugh|sigh|gasp)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_OR_PAREN = Pattern.compile("\\[[^\\]]{1,60}]|\\([^)]{1,60}\\)");
    private static final Pattern STAGE_WORDS = Pattern.compile("(?i)(voice|pause|laugh|tone|dramatic|excited|whisper|sigh|beat|emotion|energetic|playful|slowly|quickly|music|sfx|applause|chuckle|giggle|smile|serious|calm|curious|encouraging)");
    private static final Pattern SPEAKER_LABEL = Pattern.compile("^\\s*(narrator|tutor|teacher|voice ?over|speaker)\\s*[:\\-]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSCRIBE = Pattern.compile("(?i)subscribe");

    public Report check(List<SceneDraft> scenes, Context ctx) {
        Report rep = new Report();
        if (scenes == null || scenes.isEmpty()) {
            rep.add(Level.ERROR, 0, "no-scenes", "The lesson has no scenes.");
            return rep;
        }
        for (int i = 0; i < scenes.size(); i++) {
            SceneDraft s = scenes.get(i);
            s.number = i + 1;
            repairScene(s, i, ctx, rep);
        }
        checkOpening(scenes.get(0), ctx, rep);
        checkClosing(scenes.get(scenes.size() - 1), ctx, rep);
        for (SceneDraft s : scenes) checkScene(s, ctx, rep);
        checkRepeats(scenes, rep);
        checkOrder(scenes, ctx, rep);
        return rep;
    }

    // ------------------------------------------------------------------ deterministic repairs

    private void repairScene(SceneDraft s, int index, Context ctx, Report rep) {
        // stage directions / speaker labels must never be spoken
        List<String> clean = new ArrayList<>();
        boolean changed = false;
        for (String sentence : s.narration) {
            String c = sanitize(sentence);
            if (!c.equals(sentence.trim())) changed = true;
            if (!c.isBlank()) clean.add(c);
        }
        if (changed) rep.add(Level.FIXED, s.number, "stage-direction", "Removed stage directions / labels from the narration so they are not spoken.");
        s.narration = clean;

        VoiceDirection vd = VoiceDirection.of(s.emotion, s.pace, s.delivery, ctx.style() == null ? "neutral" : ctx.style().baseEmotion());
        if (vd.hadUnsupported()) rep.add(Level.FIXED, s.number, "voice-values", "Unsupported voice-direction value mapped to the nearest supported one.");
        s.emotion = vd.emotion();
        s.pace = vd.pace();
        s.delivery = vd.delivery();

        if (blank(s.sectionType) && ctx.outlineSections() != null && index < ctx.outlineSections().size()) s.sectionType = ctx.outlineSections().get(index);
        if (blank(s.objective)) {
            if (ctx.outlineObjectives() != null && index < ctx.outlineObjectives().size() && !blank(ctx.outlineObjectives().get(index))) {
                s.objective = ctx.outlineObjectives().get(index);
                rep.add(Level.FIXED, s.number, "objective", "Learning objective taken from the plan.");
            }
        }
        if (blank(s.visualDescription)) {
            s.visualDescription = "Slide \"" + nz(s.title) + "\" showing " + (s.slideTerms.isEmpty() ? "the idea explained in this scene"
                    : String.join(", ", s.slideTerms.stream().limit(5).toList())) + ".";
            rep.add(Level.FIXED, s.number, "visual-description", "Visual description derived from the slide content.");
        }
        if (!VALID_TEMPLATES.contains(nz(s.template))) {
            s.template = "definition";
            rep.add(Level.FIXED, s.number, "template", "Unknown slide template replaced by 'definition'.");
        }
    }

    /** Public so tests (and the service) can reuse it. */
    public static String sanitize(String sentence) {
        if (sentence == null) return "";
        String t = SPEAKER_LABEL.matcher(sentence).replaceFirst("");
        // keep allowed performance cues, drop every other [..] / (..) that looks like a stage direction
        java.util.regex.Matcher m = BRACKET_OR_PAREN.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            if (ALLOWED_CUE.matcher(g).matches()) { m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(g)); continue; }
            String inner = g.substring(1, g.length() - 1);
            boolean direction = g.startsWith("[") || STAGE_WORDS.matcher(inner).find();
            m.appendReplacement(sb, direction ? "" : java.util.regex.Matcher.quoteReplacement(g)); // e.g. "(a, b)" in real prose stays
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s{2,}", " ").replaceAll("\\s+([,.!?])", "$1").trim();
    }

    // ------------------------------------------------------------------ opening / closing

    private void checkOpening(SceneDraft first, Context ctx, Report rep) {
        if (first.narration.isEmpty()) return; // reported as missing narration
        String opening = TeachingPlanner.openingLine(ctx.topic());
        String greeting = TeachingPlanner.greeting(ctx.topic(), ctx.language());
        String full = greeting.isEmpty() ? opening : greeting + " " + opening;
        String s0 = first.narration.get(0);
        if (TeachingPlanner.isEnglishFamily(ctx.language())) {
            // narration arrives scrubbed (NarrationScrub removes stacked/leading greetings), so s0 has no greeting here
            String norm = norm(s0);
            boolean startsRight = norm.startsWith("what are we learning today");
            String topicNorm = norm(TeachingPlanner.cleanTopic(ctx.topic()));
            boolean hasTopic = startsRight && topicWordsPresent(norm, topicNorm);
            if (!startsRight) {
                // merged into the first sentence (not a new one) so sentence k still explains slide element k
                first.narration.set(0, full + " " + s0);
                rep.add(Level.FIXED, 1, "opening", "Added the required opening: \"" + full + "\".");
            } else if (!hasTopic) {
                first.narration.set(0, full);
                rep.add(Level.FIXED, 1, "opening", "Opening did not name the topic - replaced with \"" + full + "\".");
            } else {
                first.narration.set(0, greeting + " " + s0);
                rep.add(Level.FIXED, 1, "greeting", "Added one simple greeting: \"" + greeting + "\".");
            }
        } else {
            if (!greeting.isEmpty() && !s0.startsWith(greeting)) {
                first.narration.set(0, greeting + " " + s0);
                rep.add(Level.FIXED, 1, "greeting", "Added one simple greeting: \"" + greeting + "\".");
            }
            if (!s0.contains("?") && !s0.contains("!") && !s0.contains("\uFF1F")) {
                rep.add(Level.WARN, 1, "opening", "The opening should be a topic-introducing question or exclamation in " + ctx.language() + ".");
            }
        }
        if (first.sectionType != null && SectionType.parse(first.sectionType) != SectionType.HOOK) {
            rep.add(Level.WARN, 1, "first-section", "The first scene is not a HOOK.");
        }
    }

    private void checkClosing(SceneDraft last, Context ctx, Report rep) {
        if (last.narration.isEmpty()) return;
        boolean english = TeachingPlanner.isEnglishFamily(ctx.language());
        String all = last.narrationText();
        if (ctx.destination() == Destination.SOCIAL) {
            if (english && !SUBSCRIBE.matcher(all).find()) {
                last.narration.add("If this helped you understand it, subscribe for more simple and entertaining lessons!");
                rep.add(Level.FIXED, last.number, "closing", "Added the friendly subscribe closing for social-video destination.");
            }
        } else if (SUBSCRIBE.matcher(all).find()) {
            last.narration.removeIf(x -> SUBSCRIBE.matcher(x).find());
            rep.add(Level.FIXED, last.number, "closing", "Removed the subscribe request (classroom / internal destination).");
        }
        if (last.sectionType != null && SectionType.parse(last.sectionType) != SectionType.RECAP) {
            rep.add(Level.WARN, last.number, "last-section", "The last scene is not a RECAP.");
        }
    }

    // ------------------------------------------------------------------ per-scene checks

    private void checkScene(SceneDraft s, Context ctx, Report rep) {
        int n = s.number;
        if (s.narration.isEmpty()) {
            rep.add(Level.ERROR, n, "narration-missing", "narration is empty.");
            return;
        }
        if (blank(s.objective)) rep.add(Level.ERROR, n, "objective-missing", "learningObjective is missing.");
        if (blank(s.title)) rep.add(Level.WARN, n, "title-missing", "title is missing.");
        String t = nz(s.template);
        int need;
        switch (t) {
            case "definition" -> need = s.count("text") + s.count("callouts") > 0 ? 1 : 0;
            case "analogy" -> need = s.count("callouts") + s.count("text") > 0 ? 1 : 0;
            case "analogy_code", "code_block" -> need = s.count("code") > 0 ? 1 : 0;
            case "code_anatomy" -> need = s.count("code") > 0 && s.count("parts") >= 2 ? 1 : 0;
            case "table" -> need = s.count("rows") >= 2 && s.count("columns") >= 2 ? 1 : 0;
            case "code_visual" -> need = s.count("code") > 0 && s.count("box") > 0 ? 1 : 0;
            case "example_list", "checklist" -> need = s.count("items") >= 2 ? 1 : 0;
            case "summary" -> need = s.count("statement") > 0 && s.count("mappings") >= 2 ? 1 : 0;
            case "flow" -> need = s.flowLabels.size() >= 2 ? 1 : 0;
            case "compare" -> need = s.count("left") >= 1 && s.count("right") >= 1 ? 1 : 0;
            default -> need = 1;
        }
        if (need == 0) rep.add(Level.ERROR, n, "slide-content", "slide content is missing or too thin for template '" + t + "'.");
        if ((t.equals("analogy") || t.equals("analogy_code")) && blank(s.imagePrompt)) {
            rep.add(Level.ERROR, n, "image-prompt-missing", "imagePrompt is empty but this scene needs an illustration (derive it from the narration).");
        }
        if (CODE_TEMPLATES.contains(t) && ctx.kind() != null && !ctx.kind().codeFriendly()) {
            rep.add(Level.WARN, n, "code-on-non-code-topic", "A code slide is used on a non-programming topic.");
        }

        Set<String> narrOnly = tokens(s.narrationText());          // what the tutor actually says
        Set<String> narr = tokens(s.narrationText() + " " + nz(s.title));
        Set<String> slide = new HashSet<>();
        for (String term : s.slideTerms) slide.addAll(tokens(term));
        slide.addAll(tokens(nz(s.title)));
        if (!slide.isEmpty() && !narrOnly.isEmpty() && java.util.Collections.disjoint(slide, narrOnly)) {
            rep.add(Level.WARN, n, "slide-unrelated", "slide text shares no words with the narration - check that they explain the same thing.");
        }
        if (!blank(s.imagePrompt)) {
            Set<String> img = tokens(s.imagePrompt);
            if (!img.isEmpty() && java.util.Collections.disjoint(img, narr)) {
                rep.add(Level.WARN, n, "image-unrelated", "imagePrompt shares no words with the narration of this scene.");
            }
        }
        if (t.equals("flow") && s.flowLabels.size() >= 2) checkFlowOrder(s, rep);
        int words = s.narrationText().split("\\s+").length;
        if (words < 5) rep.add(Level.WARN, n, "narration-short", "narration is very short (" + words + " words).");
    }

    /** Diagram labels must be mentioned in the narration, in the same order as the nodes. */
    private void checkFlowOrder(SceneDraft s, Report rep) {
        String narr = norm(s.narrationText());
        int mentioned = 0, last = -1;
        boolean ordered = true;
        for (String label : s.flowLabels) {
            String key = norm(label);
            int at = key.isEmpty() ? -1 : narr.indexOf(key);
            if (at < 0) { // try the first significant word of the label
                for (String w : key.split(" ")) if (w.length() >= 4 && !STOP.contains(w)) { at = narr.indexOf(w); if (at >= 0) break; }
            }
            if (at >= 0) {
                mentioned++;
                if (at < last) ordered = false;
                last = Math.max(last, at);
            }
        }
        if (mentioned * 2 < s.flowLabels.size()) rep.add(Level.WARN, s.number, "flow-labels", "most diagram labels are never mentioned in the narration.");
        else if (!ordered) rep.add(Level.ERROR, s.number, "flow-order", "the diagram nodes are in a different order than the narration explains them - reorder the nodes (or the sentences) so they match.");
    }

    // ------------------------------------------------------------------ cross-scene checks

    private void checkRepeats(List<SceneDraft> scenes, Report rep) {
        Map<String, Integer> seen = new HashMap<>();
        for (SceneDraft s : scenes) {
            for (String sentence : s.narration) {
                String k = norm(sentence);
                if (k.split(" ").length < 5) continue;
                Integer first = seen.putIfAbsent(k, s.number);
                if (first != null) rep.add(Level.ERROR, s.number, "repeated-sentence", "repeats a sentence from scene " + first + ".");
            }
        }
        for (int i = 0; i < scenes.size(); i++) {
            Set<String> a = tokens(scenes.get(i).narrationText());
            if (a.size() < 6) continue;
            for (int j = i + 1; j < scenes.size(); j++) {
                Set<String> b = tokens(scenes.get(j).narrationText());
                if (b.size() < 6) continue;
                Set<String> inter = new HashSet<>(a);
                inter.retainAll(b);
                double jac = inter.size() / (double) (a.size() + b.size() - inter.size());
                if (jac >= 0.78) rep.add(Level.ERROR, scenes.get(j).number, "repeated-scene", "narration is almost identical to scene " + scenes.get(i).number + ".");
            }
        }
    }

    private void checkOrder(List<SceneDraft> scenes, Context ctx, Report rep) {
        List<String> plan = ctx.outlineSections();
        if (plan == null || plan.isEmpty()) return;
        if (scenes.size() != plan.size()) {
            rep.add(Level.WARN, 0, "scene-count", "The lesson has " + scenes.size() + " scenes but the plan had " + plan.size() + ".");
            return;
        }
        for (int i = 0; i < plan.size(); i++) {
            SectionType want = SectionType.parse(plan.get(i)), got = SectionType.parse(scenes.get(i).sectionType);
            if (want != null && got != null && want != got) {
                rep.add(Level.ERROR, i + 1, "scene-order", "scene order differs from the plan (expected " + want.name() + " here, got " + got.name() + ").");
                return;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean topicWordsPresent(String sentenceNorm, String topicNorm) {
        String[] words = topicNorm.split(" ");
        int hit = 0, total = 0;
        for (String w : words) {
            if (w.length() < 2) continue;
            total++;
            if (sentenceNorm.contains(w)) hit++;
        }
        return total == 0 || hit * 2 >= total;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
