package com.aistorystudio.conceptexplainer.tutorial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Calls the isolated renderer service. Generated model text is data only; no generated JS is executed by the backend. */
public final class VisualEngineRenderClient {
    private static final ObjectMapper M = new ObjectMapper();
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public VisualEngineRenderClient(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""); }
    public boolean enabled() { return !baseUrl.isBlank(); }

    /** What the renderer service can export right now (from its /health). All false when it is not configured or not reachable. */
    public record Caps(boolean threeJs, boolean rive, boolean pixiJs, boolean motionCanvas, String motionReason, String note, boolean motionClips, boolean threeClips, boolean pixiClips) {
        public Caps(boolean threeJs, boolean rive, boolean pixiJs, boolean motionCanvas, String motionReason, String note, boolean motionClips, boolean threeClips) { this(threeJs, rive, pixiJs, motionCanvas, motionReason, note, motionClips, threeClips, false); }
        public Caps(boolean threeJs, boolean rive, boolean motionCanvas, String motionReason, String note, boolean motionClips, boolean threeClips) { this(threeJs, rive, false, motionCanvas, motionReason, note, motionClips, threeClips, false); }
        public Caps(boolean threeJs, boolean rive, boolean motionCanvas, String motionReason, String note, boolean motionClips) { this(threeJs, rive, false, motionCanvas, motionReason, note, motionClips, false, false); }
        public Caps(boolean threeJs, boolean rive, boolean motionCanvas, String motionReason, String note) { this(threeJs, rive, false, motionCanvas, motionReason, note, false, false, false); }
        public Caps(boolean threeJs, boolean rive, boolean motionCanvas) { this(threeJs, rive, false, motionCanvas, "", "", false, false, false); }
    }

    private static volatile Caps cachedCaps;
    private static volatile long capsAt;

    public Caps capabilities() {
        if (!enabled()) return new Caps(false, false, false, false, "", "renderer base URL is not configured", false, false);
        long now = System.currentTimeMillis();
        Caps c = cachedCaps;
        if (c != null && now - capsAt < (c.threeJs() ? 30_000 : 5_000)) return c;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = r.statusCode() == 200 ? M.readTree(r.body()) : M.createObjectNode();
            JsonNode e = root.path("engines");
            c = new Caps(e.path("THREE_JS").asBoolean(false), e.path("RIVE").asBoolean(false), e.path("PIXIJS").asBoolean(false), e.path("MOTION_CANVAS").asBoolean(false),
                    root.path("capabilities").path("MOTION_CANVAS").path("reason").asText(""), r.statusCode() == 200 ? "" : "renderer answered HTTP " + r.statusCode(),
                    root.path("clips").path("MOTION_CANVAS").asBoolean(false), root.path("clips").path("THREE_JS").asBoolean(false), root.path("clips").path("PIXIJS").asBoolean(false));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            c = new Caps(false, false, false, false, "", "the renderer service is not reachable at " + baseUrl, false, false);
        }
        cachedCaps = c; capsAt = now;
        return c;
    }

    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset) throws IOException, InterruptedException {
        return render(engine, title, narration, planJson, steps, width, height, riveAsset, null);
    }

    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset, String sceneKind) throws IOException, InterruptedException {
        return render(engine, title, narration, planJson, steps, width, height, riveAsset, sceneKind, null);
    }

    /** {@code theme} is the lesson's look (reel, reference ...): the 3D / animated scene takes its background so it matches the rest of the video. */
    public List<byte[]> render(String engine, String title, String narration, String planJson, int steps,
                               int width, int height, Path riveAsset, String sceneKind, String theme) throws IOException, InterruptedException {
        if (!enabled()) throw new IOException("VISUAL_RENDERER_BASE_URL is not configured");
        ObjectNode payload = M.createObjectNode();
        payload.put("engine", engine); payload.put("title", safe(title)); payload.put("narration", safe(narration));
        payload.put("planJson", planJson == null ? "{}" : planJson); payload.put("steps", Math.max(2, Math.min(8, steps)));
        payload.put("width", width); payload.put("height", height);
        if (sceneKind != null && !sceneKind.isBlank()) payload.put("sceneKind", sceneKind);
        String kick = kickerOf(planJson); if (!kick.isBlank()) payload.put("kicker", kick);
        if (theme != null && !theme.isBlank()) payload.put("theme", theme);
        JsonNode plan; try { plan = M.readTree(planJson == null ? "{}" : planJson); } catch (Exception e) { plan = M.createObjectNode(); }
        List<String> labels = labelsFor(plan, sceneKind);
        // no usable device in the plan: say the scene's own words, not the template's placeholders ("Start / Process / Result", "Take order / Brew tea")
        boolean placeholderKind = sceneKind == null || sceneKind.isBlank() || List.of("lanes", "layers", "flow", "nodes").contains(sceneKind);
        if (labels.size() < 3 && placeholderKind) { List<String> derived = deriveLabels(narration, 4); if (derived.size() >= 3) labels = derived; }
        payload.set("labels", M.valueToTree(labels));
        if ("RIVE".equalsIgnoreCase(engine)) {
            if (riveAsset == null || !Files.isRegularFile(riveAsset)) throw new IOException("Rive asset is missing or inaccessible; refusing to report a Rive render.");
            if (Files.size(riveAsset) > 25L * 1024 * 1024) throw new IOException("Rive asset exceeds the 25 MB render limit.");
            payload.put("riveAssetBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(riveAsset)));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/render-frames"))
                .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(payload))).build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Visual renderer returned HTTP " + response.statusCode() + ": " + response.body());
        JsonNode root = M.readTree(response.body());
        if (!"RENDERED".equals(root.path("rendererStatus").asText())) throw new IOException("Visual renderer did not confirm a completed render.");
        List<byte[]> frames = new ArrayList<>();
        for (JsonNode frame : root.path("frames")) frames.add(Base64.getDecoder().decode(frame.asText()));
        if (frames.size() < 2) throw new IOException("Visual renderer returned fewer than two frames.");
        return frames;
    }
    /**
     * The words the 3D scene should show, taken from the lesson's own plan: the layers device for a layered stack, the steps / cycle / tiles
     * for parallel work, otherwise the first device with a list. Falls back to the old top-level lists. At most 6, in plan order.
     */
    static List<String> labelsFor(JsonNode plan, String sceneKind) {
        List<String> prefer = switch (sceneKind == null ? "" : sceneKind) {
            case "layers" -> List.of("layers", "steps", "tree", "cycle");
            case "stack" -> List.of("datastruct", "tree", "steps", "tile", "card");
            case "lanes", "flow" -> List.of("steps", "cycle", "sequence", "tile", "layers");
            case "orbit", "molecule" -> List.of("cycle", "tree", "tile", "steps", "layers");
            default -> List.of("steps", "layers", "cycle", "tree", "tile");
        };
        List<String> labels = new ArrayList<>();
        JsonNode elements = plan.path("elements");
        if (elements.isArray()) {
            for (String type : prefer) {
                for (JsonNode e : elements) {
                    if (!type.equals(e.path("type").asText("").toLowerCase(java.util.Locale.ROOT))) continue;
                    if (e.path("lines").isArray()) for (JsonNode ln : e.path("lines")) addLabel(labels, ln.asText(""));
                    else addLabel(labels, e.path("text").asText(""));
                }
                if (labels.size() >= 3) return labels;
                labels.clear();
            }
            // any device that carries a short list or a name
            for (JsonNode e : elements) {
                if (e.path("lines").isArray()) for (JsonNode ln : e.path("lines")) addLabel(labels, ln.asText(""));
                if (labels.size() >= 3) return labels;
            }
            labels.clear();
        }
        for (String field : List.of("items", "nodes", "steps", "parts")) {
            JsonNode arr = plan.path(field);
            if (arr.isArray()) for (JsonNode n : arr) addLabel(labels, n.isTextual() ? n.asText() : n.path("label").asText(n.path("title").asText("")));
            if (labels.size() >= 3) break;
        }
        return labels;
    }

    private static final java.util.Set<String> LEAD_WORDS = java.util.Set.of("so", "now", "but", "and", "then", "also", "okay", "well", "first", "next", "finally", "let's", "lets", "here's", "this", "that's", "thats");
    private static final java.util.Set<String> TRAIL_WORDS = java.util.Set.of("to", "the", "a", "an", "of", "and", "or", "that", "this", "we", "should", "is", "are", "do", "does", "it", "in", "on", "for", "with", "at", "by", "but", "so", "just", "can", "will", "you", "your", "our", "they");

    /**
     * Short labels from the scene's own sentences, for scenes whose plan carried no list to show: the opening words of each statement
     * (questions and one-word sentences are skipped), cleaned of leading connectors and trailing small words. At most {@code max}.
     */
    static List<String> deriveLabels(String narration, int max) {
        List<String> out = new ArrayList<>();
        if (narration == null) return out;
        // on-screen text stays in English for every narration language: never lift labels out of Hindi / Tamil / ... narration
        long letters = narration.chars().filter(Character::isLetter).count(), latin = narration.chars().filter(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')).count();
        if (letters == 0 || latin < letters * 0.9) return out;
        for (String sentence : narration.split("(?<=[.!?\\u0964])\\s+")) {
            String t = sentence.trim();
            if (t.isEmpty() || t.endsWith("?")) continue;
            List<String> words = new ArrayList<>(List.of(t.replaceAll("[\\p{Cntrl}<>\"]", " ").replaceAll("[.!:;]+$", "").split("\\s+")));
            while (words.size() > 2 && LEAD_WORDS.contains(words.get(0).toLowerCase(java.util.Locale.ROOT).replaceAll("[,.]", ""))) words.remove(0);
            List<String> take = new ArrayList<>();
            for (String w : words) { take.add(w.replaceAll(",$", "")); if (take.size() >= 4 || w.endsWith(",")) break; }
            while (take.size() > 1 && TRAIL_WORDS.contains(take.get(take.size() - 1).toLowerCase(java.util.Locale.ROOT))) take.remove(take.size() - 1);
            if (take.size() < 2) continue;
            while (take.size() > 2 && String.join(" ", take).length() > 24) take.remove(take.size() - 1);   // cut whole words, never mid-word
            String label = String.join(" ", take);
            label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            if (label.length() > 24) label = label.substring(0, 23).trim() + "\u2026";
            if (!out.contains(label)) out.add(label);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** The small heading above a scene's title ("02 · 1865"): the plan's own label, so 3D and animated scenes carry the same heading as the native ones. */
    public static String kickerOf(String planJson) {
        try { return safe(M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("label").asText("")); } catch (Exception e) { return ""; }
    }

    /** The cards / compare / bars of a plan, trimmed to what the animated board needs. */
    public static com.fasterxml.jackson.databind.node.ArrayNode boardElements(String planJson) {
        var out = M.createArrayNode();
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements")) {
                String type = e.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("card", "tile", "note", "compare", "bars").contains(type)) continue;
                ObjectNode o = M.createObjectNode(); o.put("type", type);
                for (String f : new String[] {"title", "text"}) if (e.path(f).isTextual()) o.put(f, e.path(f).asText());
                if (e.path("lines").isArray()) o.set("lines", e.path("lines"));
                for (String f : new String[] {"x", "y", "w", "at"}) if (e.path(f).isNumber()) o.put(f, e.path(f).asDouble());
                out.add(o);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Cards / compare tables / bars as an animated clip as long as the narration. */
    public byte[] renderBoardClip(String title, String kicker, String planJson, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        ObjectNode p = M.createObjectNode();
        p.put("engine", "MOTION_CANVAS"); p.put("template", "board"); p.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        p.set("elements", boardElements(planJson)); p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    /** The first code device of a plan: {file name, code}; code is empty when the plan has none. */
    public static String[] codeOf(String planJson) {
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements"))
                if ("code".equalsIgnoreCase(e.path("type").asText(""))) return new String[] {e.path("title").asText("Example.java"), e.path("content").asText("")};
        } catch (Exception ignored) { }
        return new String[] {"Example.java", ""};
    }

    /** A code window typed in while the narration explains it. */
    public byte[] renderCodeClip(String title, String kicker, String planJson, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        String[] code = codeOf(planJson);
        ObjectNode p = M.createObjectNode();
        p.put("engine", "MOTION_CANVAS"); p.put("template", "code"); p.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        p.put("file", code[0]); p.put("code", code[1]); p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    private static final java.util.Set<String> DIAGRAM_TYPES = java.util.Set.of("tile", "actor", "button", "heading", "pill", "label", "note", "quote", "card", "container", "arrow", "message", "icon");

    /** The diagram elements of a plan in the form the renderer expects (unknown types are dropped; the renderer validates every field again). */
    public static com.fasterxml.jackson.databind.node.ArrayNode diagramElements(String planJson) {
        var out = M.createArrayNode();
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements"))
                if (DIAGRAM_TYPES.contains(e.path("type").asText("").toLowerCase(java.util.Locale.ROOT))) out.add(e);
        } catch (Exception ignored) { }
        return out;
    }

    /** The planner's diagram (tiles, actors, arrows ...) as an animated clip as long as the narration. */
    public byte[] renderDiagramClip(String title, String kicker, String planJson, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        ObjectNode p = M.createObjectNode();
        p.put("engine", "MOTION_CANVAS"); p.put("template", "diagram"); p.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        p.set("elements", diagramElements(planJson)); p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    /** The formulas of a plan: {tex, label, caption, at} each, in plan order. */
    public static com.fasterxml.jackson.databind.node.ArrayNode formulaList(String planJson) {
        var out = M.createArrayNode();
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements")) {
                if (!"formula".equalsIgnoreCase(e.path("type").asText(""))) continue;
                ObjectNode f = M.createObjectNode();
                f.put("tex", e.path("text").asText(e.path("title").asText("")));
                f.put("label", e.path("title").asText(""));
                // the legend ("F|net force (N)", "m|mass (kg)") reads "F = net force (N)  ·  m = mass (kg)"; a plain caption line stays as it is
                List<String> legend = new ArrayList<>();
                for (JsonNode l : e.path("lines")) { String[] pr = l.asText("").split("\\|", 2); legend.add(pr.length == 2 && !pr[0].isBlank() ? pr[0].trim() + " = " + pr[1].trim() : l.asText("").trim()); if (legend.size() == 3) break; }
                f.put("caption", String.join("   \u00B7   ", legend));
                f.put("at", e.path("at").asInt(0));
                out.add(f);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The chart of a plan: style, unit, title and the "label|value" points as [{label, value}]. Empty data when the plan has no usable chart. */
    public static ObjectNode chartOf(String planJson) {
        ObjectNode out = M.createObjectNode(); var data = out.putArray("data");
        out.put("style", "line"); out.put("unit", ""); out.put("title", "");
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements")) {
                if (!"chart".equalsIgnoreCase(e.path("type").asText(""))) continue;
                String st = e.path("style").asText("line").toLowerCase(java.util.Locale.ROOT);
                out.put("style", java.util.Set.of("line", "area", "bar", "donut").contains(st) ? st : "line");
                out.put("unit", e.path("text").asText("")); out.put("title", e.path("title").asText(""));
                for (JsonNode l : e.path("lines")) {
                    String[] parts = l.asText("").split("\\|");
                    if (parts.length < 2) continue;
                    var m = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(parts[1]);
                    if (!m.find()) continue;
                    ObjectNode d = M.createObjectNode(); d.put("label", parts[0].trim()); d.put("value", Double.parseDouble(m.group().replace(',', '.'))); data.add(d);
                }
                break;
            }
        } catch (Exception ignored) { }
        return out;
    }

    public byte[] renderFormulaClip(String title, String kicker, String planJson, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        ObjectNode p = M.createObjectNode();
        p.put("engine", "MOTION_CANVAS"); p.put("template", "formula"); p.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        p.set("formulas", formulaList(planJson)); p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    public byte[] renderChartClip(String title, String kicker, String planJson, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        ObjectNode chart = chartOf(planJson);
        ObjectNode p = M.createObjectNode();
        p.put("engine", "MOTION_CANVAS"); p.put("template", "chart");
        p.put("title", chart.path("title").asText("").isBlank() ? (title == null ? "" : title) : chart.path("title").asText(""));
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        p.put("chartStyle", chart.path("style").asText("line")); p.put("unit", chart.path("unit").asText(""));
        p.set("data", chart.path("data")); p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    private static JsonNode firstOf(String planJson, String type) {
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements"))
                if (type.equalsIgnoreCase(e.path("type").asText(""))) return e;
        } catch (Exception ignored) { }
        return null;
    }

    /** Free-body forces from "dir|symbol|name|size" lines: [{dir, symbol, name, size}]. */
    public static com.fasterxml.jackson.databind.node.ArrayNode forcesOf(String planJson) {
        var out = M.createArrayNode(); JsonNode e = firstOf(planJson, "forces");
        if (e == null) return out;
        for (JsonNode l : e.path("lines")) {
            String[] p = l.asText("").split("\\|");
            if (p.length < 2) continue;
            ObjectNode f = M.createObjectNode(); f.put("dir", p[0].trim().toLowerCase(java.util.Locale.ROOT)); f.put("symbol", p[1].trim()); f.put("name", p.length > 2 ? p[2].trim() : "");
            double size = 1; try { if (p.length > 3) size = Double.parseDouble(p[3].trim()); } catch (NumberFormatException ignored) { }
            f.put("size", size); out.add(f);
        }
        return out;
    }

    /** Quiz: {question, options[], correct (1-based, 0 when unknown), revealAt (the sentence that gives the answer)}. */
    public static ObjectNode quizOf(String planJson) {
        ObjectNode out = M.createObjectNode(); var opts = out.putArray("options"); out.put("question", ""); out.put("correct", 0); out.put("revealAt", 1);
        JsonNode e = firstOf(planJson, "quiz");
        if (e == null) return out;
        out.put("question", e.path("title").asText(e.path("text").asText("")));
        for (JsonNode l : e.path("lines")) opts.add(l.asText(""));
        var m = java.util.regex.Pattern.compile("\\d+").matcher(e.path("style").asText(""));
        if (m.find()) out.put("correct", Integer.parseInt(m.group()));
        if (e.path("highlightAt").isArray() && e.path("highlightAt").size() > 0) out.put("revealAt", e.path("highlightAt").get(0).asInt(1));
        return out;
    }

    /** Cycle: {centre, steps[]} (the part of each line before a "|"). */
    public static ObjectNode cycleOf(String planJson) {
        ObjectNode out = M.createObjectNode(); var steps = out.putArray("steps"); out.put("centre", "");
        JsonNode e = firstOf(planJson, "cycle");
        if (e == null) return out;
        out.put("centre", e.path("text").asText(""));
        for (JsonNode l : e.path("lines")) steps.add(l.asText("").split("\\|", 2)[0].trim());
        return out;
    }

    /** The mascot of a plan: {name, mood (happy | wow | thinking | wave), speeches[{text, at}]} from the mascot and speech devices. */
    public static ObjectNode mascotOf(String planJson) {
        ObjectNode out = M.createObjectNode(); var sp = out.putArray("speeches"); out.put("name", "Ollie"); out.put("mood", "happy");
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements")) {
                String ty = e.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
                if (ty.equals("mascot")) {
                    if (!e.path("text").asText("").isBlank()) out.put("name", e.path("text").asText());
                    String st = e.path("style").asText("happy").toLowerCase(java.util.Locale.ROOT);
                    out.put("mood", java.util.Set.of("happy", "wow", "thinking", "wave").contains(st) ? st : "happy");
                } else if (ty.equals("speech") && !e.path("text").asText("").isBlank()) {
                    ObjectNode x = M.createObjectNode(); x.put("text", e.path("text").asText()); x.put("at", e.path("at").asInt(0)); sp.add(x);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Quiz / forces / cycle / mascot scenes as animated clips. {@code template} is "quiz", "forces" or "cycle". */
    public byte[] renderScienceClip(String template, String title, String kicker, String planJson, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        ObjectNode p = M.createObjectNode();
        p.put("engine", "MOTION_CANVAS"); p.put("template", template); p.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        switch (template) {
            case "quiz" -> { ObjectNode q = quizOf(planJson); p.put("question", q.path("question").asText("")); p.set("options", q.path("options")); p.put("correct", q.path("correct").asInt(0)); p.put("revealAt", q.path("revealAt").asInt(1)); }
            case "forces" -> { JsonNode e = firstOf(planJson, "forces"); p.put("object", e == null ? "" : e.path("text").asText("")); p.set("forces", forcesOf(planJson)); }
            case "mascot" -> { ObjectNode m = mascotOf(planJson); p.put("name", m.path("name").asText("Ollie")); p.put("mood", m.path("mood").asText("happy")); p.set("speeches", m.path("speeches")); }
            default -> { ObjectNode c = cycleOf(planJson); p.put("centre", c.path("centre").asText("")); p.set("steps", c.path("steps")); }
        }
        p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    /** A 3D scene as an animated clip: each element appears when the voice reaches its sentence and the camera keeps moving. */
    public byte[] renderThreeClip(String title, String kicker, String narration, List<String> labels, String sceneKind, List<Double> sentenceStarts, String theme, int width, int height, double totalSeconds)
            throws IOException, InterruptedException {
        ObjectNode p = M.createObjectNode();
        p.put("engine", "THREE_JS"); p.put("title", title == null ? "" : title); p.put("narration", narration == null ? "" : narration);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        if (sceneKind != null && !sceneKind.isBlank()) p.put("sceneKind", sceneKind);
        p.set("labels", M.valueToTree(labels)); p.set("starts", M.valueToTree(sentenceStarts));
        p.put("total", totalSeconds); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    /** PixiJS particle/flow clip with narration-aligned label reveal times, encoded as MP4 by the renderer service. */
    public byte[] renderPixiClip(String title, String kicker, List<String> labels, String sceneKind, List<Double> sentenceStarts,
                                 String theme, int width, int height, double totalSeconds) throws IOException, InterruptedException {
        ObjectNode p = M.createObjectNode();
        p.put("engine", "PIXIJS"); p.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) p.put("kicker", kicker);
        if (sceneKind != null && !sceneKind.isBlank()) p.put("sceneKind", sceneKind);
        p.set("labels", M.valueToTree(labels == null ? List.of() : labels.stream().limit(4).toList()));
        p.set("starts", M.valueToTree(sentenceStarts == null ? List.of() : sentenceStarts.stream().limit(4).toList()));
        p.put("total", Math.max(2, Math.min(120, totalSeconds))); p.put("width", width); p.put("height", height);
        if (theme != null && !theme.isBlank()) p.put("theme", theme);
        return postClip(p);
    }

    private byte[] postClip(ObjectNode payload) throws IOException, InterruptedException {
        if (!enabled()) throw new IOException("Visual renderer service is not configured.");
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/render-clip")).timeout(Duration.ofSeconds(420))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || r.body().length < 1000) {
            String reason = "HTTP " + r.statusCode();
            try { reason = M.readTree(r.body()).path("error").asText(reason); } catch (Exception ignored) { }
            throw new IOException("animated clip failed: " + reason);
        }
        return r.body();
    }

    /** Step details for the animated flow: "Net force" + "10 N (weight and normal cancel)" -> "Net force|10 N (weight and normal cancel)" (the flow scene shows the detail under the label). */
    public static List<String> withDetails(List<String> labels, String planJson) {
        List<String> out = new ArrayList<>(labels);
        try {
            for (JsonNode e : M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson).path("elements")) {
                String ty = e.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("steps", "timeline", "journey", "flowchart", "sequence").contains(ty) || !e.path("lines").isArray() || e.path("lines").size() < out.size()) continue;
                for (int i = 0; i < out.size(); i++) {
                    String[] pr = e.path("lines").get(i).asText("").split("[|:\u2014]", 2);
                    String detail = pr.length == 2 ? pr[1].replaceAll("[\\p{Cntrl}<>|]", " ").replaceAll("\\s+", " ").trim() : "";
                    if (!detail.isBlank()) out.set(i, out.get(i) + "|" + (detail.length() > 60 ? detail.substring(0, 59).trim() + "\u2026" : detail));
                }
                break;
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The words a scene's animated flow shows: the plan's own devices first, then the scene's sentences (same rule as the still-frame export). */
    public static List<String> labelsForScene(String planJson, String sceneKind, String narration) {
        JsonNode plan;
        try { plan = M.readTree(planJson == null || planJson.isBlank() ? "{}" : planJson); } catch (Exception e) { plan = M.createObjectNode(); }
        List<String> labels = labelsFor(plan, sceneKind);
        if (labels.size() < 3) { List<String> derived = deriveLabels(narration, 4); if (derived.size() >= 3) labels = derived; }
        return labels;
    }

    /**
     * An animated clip (mp4) of the step flow, as long as the narration, with step i appearing at {@code reveals[i]} seconds. Needs a renderer that reports
     * {@code motionClips}; throws with the renderer's own reason otherwise so the caller can fall back to still frames.
     */
    public byte[] renderMotionClip(String title, List<String> labels, String theme, int width, int height, List<Double> reveals, double totalSeconds)
            throws IOException, InterruptedException {
        return renderMotionClip(title, "", labels, theme, width, height, reveals, totalSeconds);
    }

    public byte[] renderMotionClip(String title, String kicker, List<String> labels, String theme, int width, int height, List<Double> reveals, double totalSeconds)
            throws IOException, InterruptedException {
        if (!enabled()) throw new IOException("Visual renderer service is not configured.");
        ObjectNode payload = M.createObjectNode();
        payload.put("engine", "MOTION_CANVAS"); payload.put("title", title == null ? "" : title);
        if (kicker != null && !kicker.isBlank()) payload.put("kicker", kicker);
        payload.set("labels", M.valueToTree(labels)); payload.set("reveals", M.valueToTree(reveals));
        payload.put("total", totalSeconds); payload.put("width", width); payload.put("height", height);
        if (theme != null && !theme.isBlank()) payload.put("theme", theme);
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/render-clip")).timeout(Duration.ofSeconds(420))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || r.body().length < 1000) {
            String reason = "HTTP " + r.statusCode();
            try { reason = M.readTree(r.body()).path("error").asText(reason); } catch (Exception ignored) { }
            throw new IOException("animated clip failed: " + reason);
        }
        return r.body();
    }

    /** "Apps|what you use" and "Swallow: muscles open" keep only the name before the separator; long names are shortened. */
    private static void addLabel(List<String> out, String raw) {
        if (raw == null || out.size() >= 6) return;
        String name = raw.split("[|:\u2014]", 2)[0].replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").trim();
        if (name.isBlank()) return;
        if (name.length() > 22) name = name.substring(0, 21).trim() + "\u2026";
        out.add(name);
    }
    private static String safe(String s) { return s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim(); }
}
