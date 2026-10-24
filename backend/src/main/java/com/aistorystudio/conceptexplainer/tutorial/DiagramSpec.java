package com.aistorystudio.conceptexplainer.tutorial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the model's scene JSON into a clean, fully resolved scene:
 * <ul>
 *   <li>"keep" ids from the previous scene are copied in (visible from sentence 0, same place);</li>
 *   <li>field aliases the models like to use are normalised (label/name -> text, bullets -> lines, code/output -> content ...);</li>
 *   <li>unknown types are mapped or dropped, "at" is clamped to the sentence range, arrows to missing ids are removed.</li>
 * </ul>
 * The resolved scene is stored as the scene's plan JSON, so re-rendering a single scene never needs the model again.
 */
public final class DiagramSpec {

    private static final ObjectMapper M = new ObjectMapper();

    private DiagramSpec() { }

    /**
     * @param scene     the model's scene JSON
     * @param previous  resolved elements of the previous scene (empty for the first)
     * @param sentences number of narration sentences of this scene (after cleaning)
     * @return resolved scene: {"heading", "elements": [...]} (narration is stored separately)
     */
    public static ObjectNode resolve(JsonNode scene, List<JsonNode> previous, int sentences, List<String> warnings) {
        int n = Math.max(1, sentences);
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        Map<String, JsonNode> prev = new LinkedHashMap<>();
        for (JsonNode p : previous) prev.put(p.path("id").asText(""), p);

        // 1) kept elements first (they sit "behind" the new ones and keep their place)
        for (JsonNode k : scene.path("keep")) {
            String id = k.asText("").trim();
            JsonNode p = prev.get(id);
            if (p == null) continue;
            ObjectNode c = p.deepCopy();
            boolean wasDim = c.path("dimAt").asInt(-1) >= 0;
            c.put("at", 0);
            c.put("dimAt", wasDim ? 0 : -1);
            c.remove("highlightAt");
            out.put(id, c);
        }
        // arrows between kept elements stay too, unless the model re-declares them
        for (JsonNode p : previous) {
            if (!("arrow".equals(p.path("type").asText()) || "cables".equals(p.path("type").asText())) || out.containsKey(p.path("id").asText())) continue;
            if (out.containsKey(p.path("from").asText()) && out.containsKey(p.path("to").asText())) {
                ObjectNode c = p.deepCopy();
                c.put("at", 0);
                c.put("dimAt", -1);
                c.remove("highlightAt");
                out.put(p.path("id").asText(), c);
            }
        }

        // 2) new / changed elements
        int auto = 0;
        for (JsonNode raw : scene.path("elements")) {
            if (!raw.isObject()) continue;
            ObjectNode e = normalise((ObjectNode) raw.deepCopy());
            if (e == null) continue;
            String id = e.path("id").asText("").trim();
            if (id.isEmpty()) { id = "e" + (++auto); e.put("id", id); }
            ObjectNode existing = out.get(id);
            if (existing != null && !e.has("x") && existing.has("x")) { // re-declared without a position: keep its place
                e.set("x", existing.get("x"));
                e.set("y", existing.get("y"));
                if (existing.has("w") && !e.has("w")) e.set("w", existing.get("w"));
                if (existing.has("h") && !e.has("h")) e.set("h", existing.get("h"));
            } else if (existing == null && !e.has("x") && prev.containsKey(id) && prev.get(id).has("x")) {
                e.set("x", prev.get(id).get("x"));
                e.set("y", prev.get(id).get("y"));
            }
            e.put("at", Math.max(0, Math.min(n - 1, e.path("at").asInt(0))));
            int dim = e.path("dimAt").asInt(-1);
            e.put("dimAt", dim < 0 ? -1 : Math.max(0, Math.min(n - 1, dim)));
            ArrayNode hl = M.createArrayNode();
            JsonNode h = e.path("highlightAt");
            if (h.isInt()) hl.add(Math.max(0, Math.min(n - 1, h.asInt())));
            else for (JsonNode x : h) if (x.canConvertToInt()) hl.add(Math.max(0, Math.min(n - 1, x.asInt())));
            e.set("highlightAt", hl);
            out.remove(id);
            out.put(id, e);
        }

        // 3) arrows must point at things that exist
        Set<String> ids = new HashSet<>(out.keySet());
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, ObjectNode> en : out.entrySet()) {
            ObjectNode e = en.getValue();
            String ty = e.path("type").asText();
            if (!"arrow".equals(ty) && !"cables".equals(ty)) continue;
            String f = e.path("from").asText(""), t = e.path("to").asText("");
            if (!ids.contains(f) || !ids.contains(t) || f.equals(t)) drop.add(en.getKey());
            else {
                // an arrow can't appear before both of its ends
                int at = Math.max(e.path("at").asInt(0), Math.max(out.get(f).path("at").asInt(0), out.get(t).path("at").asInt(0)));
                e.put("at", at);
            }
        }
        drop.forEach(out::remove);
        if (!drop.isEmpty() && warnings != null) warnings.add("dropped " + drop.size() + " arrow(s) pointing at missing elements");

        ObjectNode res = M.createObjectNode();
        res.put("heading", text(scene, "heading", ""));
        res.put("label", text(scene, "label", ""));
        ArrayNode arr = res.putArray("elements");
        out.values().forEach(arr::add);
        boolean manimScene = scene.path("manim").isObject();   // the animation IS the content: never add a text note on top of it
        boolean content = manimScene, breakdownLook = false;
        for (JsonNode e : arr) {
            String ty = e.path("type").asText();
            if (!List.of("journey", "photo", "particles", "arrow", "cables").contains(ty)) content = true;
            if (List.of("journey", "callout", "db", "rows", "phone", "server", "sql").contains(ty)) breakdownLook = true;
        }
        for (JsonNode p : previous) if (List.of("journey", "callout", "db").contains(p.path("type").asText())) breakdownLook = true;
        if (!content && !arr.isEmpty()) {
            // only the step tracker / a backdrop: the model forgot the scene's content - show its key line as a card
            String line = text(scene, "title", null);
            JsonNode nar = scene.path("narration");
            if (nar.isArray() && nar.size() > 0) {
                String[] w = nar.get(0).asText("").trim().split("\\s+");
                line = String.join(" ", java.util.Arrays.copyOf(w, Math.min(w.length, 10)));
            }
            if (line != null && !line.isBlank()) {
                ObjectNode c = M.createObjectNode();
                c.put("id", "keyline");
                c.put("type", breakdownLook ? "callout" : "note");
                c.put("text", line);
                c.put("x", 50);
                c.put("y", 40);
                c.put("at", 0);
                c.put("dimAt", -1);
                arr.add(c);
                if (warnings != null) warnings.add("scene had no content elements - showed its key line instead");
            }
        }
        if (arr.isEmpty() && !manimScene) { // never an empty screen: show the scene's key idea as a note
            ObjectNode note = M.createObjectNode();
            note.put("id", "keyidea");
            note.put("type", "note");
            note.put("text", text(scene, "title", "Key idea"));
            note.put("x", 50);
            note.put("y", 50);
            note.put("at", 0);
            note.put("dimAt", -1);
            arr.add(note);
        }
        return res;
    }

    /** A LaTeX formula as plain readable text for the native renderer: \\frac{a}{b} -> (a) / (b), \\sqrt{x} -> sqrt(x), \\cdot -> ·, Greek letters, ^2 -> ². Anything else loses its backslashes and braces. */
    static String plainFormula(String tex) {
        String t = tex == null ? "" : tex;
        for (int i = 0; i < 4; i++) t = t.replaceAll("\\\\[dt]?frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}", "($1) / ($2)");
        t = t.replaceAll("\\\\sqrt\\s*\\{([^{}]*)\\}", "sqrt($1)")
                .replace("\\cdot", "\u00B7").replace("\\times", "\u00D7").replace("\\pm", "\u00B1").replace("\\approx", "\u2248").replace("\\neq", "\u2260")
                .replace("\\leq", "\u2264").replace("\\geq", "\u2265").replace("\\infty", "\u221E").replace("\\pi", "\u03C0").replace("\\sum", "\u03A3").replace("\\int", "\u222B")
                .replace("\\alpha", "\u03B1").replace("\\beta", "\u03B2").replace("\\gamma", "\u03B3").replace("\\delta", "\u03B4").replace("\\theta", "\u03B8").replace("\\lambda", "\u03BB")
                .replace("\\mu", "\u03BC").replace("\\sigma", "\u03C3").replace("\\omega", "\u03C9").replace("\\Delta", "\u0394").replace("\\rightarrow", "\u2192").replace("\\to", "\u2192")
                .replace("^2", "\u00B2").replace("^3", "\u00B3").replace("^{2}", "\u00B2").replace("^{3}", "\u00B3")
                .replaceAll("\\\\[a-zA-Z]+", "").replaceAll("[{}]", "").replace("_", "").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Field aliases + type mapping. Returns null for elements that cannot be drawn. */
    static ObjectNode normalise(ObjectNode e) {
        String type = e.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        String asked = type;   // what the model called it, before mapping (array / queue / linked_list ... keep their shape)
        // the animated chart's data ("label|value" lines) on the native bars device, and a LaTeX formula as readable text: both still draw when animated clips are unavailable
        if (type.equals("chart") && e.path("lines").isArray() && e.path("lines").size() > 0) {
            type = "bars";
            String unit = e.path("text").asText("").trim();
            if (!unit.isEmpty()) {   // the chart's unit becomes the third part of each "label|value|unit" line
                com.fasterxml.jackson.databind.node.ArrayNode fixed = e.arrayNode();
                for (com.fasterxml.jackson.databind.JsonNode l : e.path("lines")) { String t = l.asText(""); fixed.add(t.split("\\|").length >= 3 ? t : t + "|" + unit); }
                e.set("lines", fixed);
            }
            e.put("text", "");
        }
        if (type.equals("formula")) {
            String tex = !e.path("text").asText("").isBlank() ? e.path("text").asText("") : e.path("title").asText("");
            e.put("text", plainFormula(tex));
            e.remove("title");
            type = "note";
        }
        type = switch (type) {
            case "box", "step", "action" -> "button";
            case "cube", "vm", "process" -> "node";
            case "number", "stat", "kpi" -> "metric";
            case "chart", "graph" -> "plot";
            case "card_table", "info", "infocard" -> "table";
            case "compare_bars", "bar" -> "bars";
            case "title_card", "intro" -> "titlecard";
            case "end", "closing" -> "outro";
            case "flow", "stepper", "procedure", "howto", "how_to", "process_steps" -> "steps";
            case "history", "chronology", "time_line" -> "timeline";
            case "versus", "vs", "comparison", "compare_table", "before_after" -> "compare";
            case "loop", "circle", "cycle_diagram" -> "cycle";
            case "stack", "pyramid", "levels" -> "layers";
            case "overlap", "venn_diagram" -> "venn";
            case "gauge_meter", "dial", "score", "level" -> "meter";
            case "todo", "dos_donts", "do_dont", "check" -> "checklist";
            case "metaphor", "like", "comparison_analogy" -> "analogy";
            case "quadrant", "2x2", "grid2x2" -> "matrix";
            case "equation", "rule", "math" -> "formula";
            case "donut", "piechart", "pie_chart" -> "pie";
            case "hierarchy", "taxonomy", "mindmap", "mind_map" -> "tree";
            case "fbd", "free_body", "freebody", "force_diagram" -> "forces";
            case "sine", "sinewave", "waveform", "sound_wave" -> "wave";
            case "electric_circuit", "circuit_diagram" -> "circuit";
            case "bohr", "atom_model", "shells" -> "atom";
            case "compound", "structure", "ball_and_stick" -> "molecule";
            case "chemical_equation", "chem_equation", "reaction_equation" -> "reaction";
            case "periodic", "periodic_tile", "element_tile" -> "element";
            case "labels", "annotate", "annotations", "labelled", "labeled" -> "pins";
            case "number_line" -> "numberline";
            case "geometry", "right_triangle" -> "triangle";
            case "fractions", "fraction_bar" -> "fraction";
            case "mcq", "question", "test" -> "quiz";
            case "fact", "didyouknow", "did_you_know", "fun_fact" -> "factcard";
            case "robot", "guide", "robo", "mascot_robot" -> "mascot";
            case "bubble", "says", "speech_bubble" -> "speech";
            case "star", "reward", "celebrate", "celebration" -> "badge";
            case "banner", "title_cloud", "cloud_title", "cloud" -> "cloudtitle";
            case "process_flow", "algorithm", "decision_flow", "flow_chart" -> "flowchart";
            case "sequence_diagram", "uml_sequence", "interaction", "request_flow" -> "sequence";
            case "array", "queue", "linked_list", "linkedlist", "hash_table", "hashmap", "data_structure" -> "datastruct";
            case "periodic_table", "elements_table" -> "periodic";
            case "energy_diagram", "energy_profile", "reaction_profile", "reaction_energy" -> "energy";
            case "ray_diagram", "lens_diagram", "optics" -> "lens";
            case "sales_funnel", "conversion_funnel", "pipeline_funnel" -> "funnel";
            case "badges", "icon_row", "icon_badges" -> "iconrow";
            case "pick", "which_one", "choose_picture" -> "choose";
            case "counting", "counter" -> "count";
            case "xp", "levelup", "level_up", "xpbar" -> "level";
            case "puzzle", "brain_teaser" -> "challenge";
            case "big_picture", "big_object" -> "bigpic";
            case "big_small", "size_compare" -> "sizes";
            case "database", "db3d", "cylinder" -> "db";
            case "rack", "server3d" -> "server";
            case "mobile", "smartphone" -> "phone";
            case "slabs", "seats", "records", "table_rows" -> "rows";
            case "swarm", "requests", "dots" -> "particles";
            case "wires", "connections", "pool" -> "cables";
            case "query", "code_card" -> "sql";
            case "image", "picture", "scene", "hero" -> "photo";
            case "progress", "tracker", "steps" -> "journey";
            case "card_callout", "kicker", "headline" -> "callout";
            case "text", "caption", "subtitle" -> "label";
            case "title" -> "heading";
            case "person", "character", "avatar" -> "actor";
            case "logo", "service", "system", "app" -> "tile";
            case "list", "bullets", "panel" -> "card";
            case "benefit", "keypoint", "key_point" -> "note";
            case "window", "console", "shell", "cli" -> "terminal";
            case "web", "page", "api_response", "json" -> "browser";
            case "snippet", "config", "file" -> "code";
            case "group", "layer", "zone" -> "container";
            case "connector", "edge", "line", "link" -> "arrow";
            case "tag", "badge" -> "pill";
            case "dialog", "speech" -> "message";
            default -> type;
        };
        if (!DiagramRenderer.TYPES.contains(type)) {
            // an element type we do not draw: keep its words on screen instead of silently losing them
            String words = firstText(e);
            if (words == null) return null;
            type = "label";
            e.put("text", words);
        }
        if (type.equals("datastruct") && e.path("style").asText("").isBlank()) {
            String shape = switch (asked) { case "queue" -> "queue"; case "linked_list", "linkedlist" -> "linkedlist"; case "hash_table", "hashmap" -> "hash"; default -> "array"; };
            e.put("style", shape);
        }
        e.put("type", type);
        alias(e, "text", "label", "name", "value", "message", "command");
        alias(e, "title", "file", "filename", "url", "app");
        alias(e, "content", "code", "output", "json", "body");
        if (!e.has("lines")) {
            for (String k : List.of("bullets", "items", "reply", "answer", "points")) {
                if (e.has(k)) { e.set("lines", e.get(k)); break; }
            }
        }
        if (e.path("lines").isTextual()) {
            ArrayNode a = M.createArrayNode();
            for (String l : e.path("lines").asText().split("\\R")) if (!l.isBlank()) a.add(l.trim());
            e.set("lines", a);
        }
        if (e.path("content").isArray() || e.path("content").isObject()) {
            JsonNode c = e.get("content");
            if (c.isArray() && c.size() > 0 && c.get(0).isTextual()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode l : c) sb.append(l.asText()).append('\n');
                e.put("content", sb.toString());
            } else {
                try { e.put("content", M.writerWithDefaultPrettyPrinter().writeValueAsString(c)); } catch (Exception ex) { e.remove("content"); }
            }
        }
        if (type.equals("pins")) alias(e, "from", "target", "on", "image", "photo");
        if (type.equals("arrow") || type.equals("cables")) {
            alias(e, "from", "source", "start");
            alias(e, "to", "target", "end");
        }
        for (String k : List.of("x", "y", "w", "h")) {
            JsonNode v = e.get(k);
            if (v == null) continue;
            double d = v.isNumber() ? v.asDouble() : parse(v.asText());
            if (Double.isNaN(d) || d < 0) { e.remove(k); continue; }
            if (d > 0 && d <= 1.0 && !(k.equals("w") || k.equals("h"))) d *= 100;    // 0-1 coordinates
            e.put(k, Math.min(160, d));   // > 100 = beyond the first view (camera pulls back)
        }
        return e;
    }

    /** Resolved scene JSON -> renderer input. */
    public static DiagramRenderer.Scene toScene(JsonNode resolved, int sentences) {
        List<DiagramRenderer.Element> els = new ArrayList<>();
        for (JsonNode e : resolved.path("elements")) {
            List<String> lines = new ArrayList<>();
            for (JsonNode l : e.path("lines")) lines.add(l.asText(""));
            List<Integer> hl = new ArrayList<>();
            for (JsonNode h : e.path("highlightAt")) hl.add(h.asInt());
            els.add(new DiagramRenderer.Element(e.path("id").asText(""), e.path("type").asText("label"),
                    text(e, "text", null), text(e, "title", null), text(e, "icon", null), text(e, "color", null),
                    lines, text(e, "content", null), text(e, "from", null), text(e, "to", null), text(e, "style", null),
                    e.path("x").asDouble(0), e.path("y").asDouble(0), e.path("w").asDouble(0), e.path("h").asDouble(0),
                    e.path("at").asInt(0), e.path("dimAt").asInt(-1), hl));
        }
        return new DiagramRenderer.Scene(text(resolved, "heading", null), els, sentences, text(resolved, "label", null));
    }

    /** Compact description of what is on screen, for the next writing batch. */
    public static String describe(List<JsonNode> elements) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : elements) {
            sb.append("- ").append(e.path("id").asText()).append(" (").append(e.path("type").asText());
            String t = text(e, "text", text(e, "title", ""));
            if (!t.isEmpty()) sb.append(" '").append(t.length() > 40 ? t.substring(0, 40) + "..." : t).append("'");
            if ("arrow".equals(e.path("type").asText())) sb.append(" ").append(e.path("from").asText()).append("->").append(e.path("to").asText());
            else sb.append(" at x=").append(Math.round(e.path("x").asDouble())).append(" y=").append(Math.round(e.path("y").asDouble()));
            sb.append(")\n");
        }
        return sb.toString();
    }

    private static String firstText(ObjectNode e) {
        for (String k : List.of("text", "label", "title", "name", "value", "content"))
            if (e.hasNonNull(k) && e.get(k).isTextual() && !e.get(k).asText().isBlank()) return e.get(k).asText().trim();
        return null;
    }

    private static void alias(ObjectNode e, String target, String... names) {
        if (e.hasNonNull(target) && !e.get(target).asText("").isBlank()) return;
        for (String n : names) {
            if (e.hasNonNull(n) && !(e.get(n).isTextual() && e.get(n).asText().isBlank())) {
                if (target.equals("content") || e.get(n).isTextual() || e.get(n).isNumber()) {
                    e.set(target, e.get(n).isTextual() || e.get(n).isNumber() ? M.getNodeFactory().textNode(e.get(n).asText()) : e.get(n));
                    return;
                }
            }
        }
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace("%", "").trim()); } catch (Exception e) { return Double.NaN; }
    }

    private static String text(JsonNode n, String f, String fb) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return fb;
        String s = v.asText("");
        return s.isBlank() ? fb : s;
    }
}
