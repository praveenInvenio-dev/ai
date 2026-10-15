package com.aistorystudio.conceptexplainer.tutorial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The studio's bundled icon library (resources/icons, built by tools/build_icon_library.py):
 * <ul>
 *   <li><b>line</b> icons (Tabler Icons, MIT): white on transparent, tinted to the lesson's colours at draw time;</li>
 *   <li><b>emoji</b> (Fluent UI Emoji 3D, MIT): colourful pictures for kids lessons and general knowledge, drawn as they are.</li>
 * </ul>
 * Names are lower snake case ("brand_docker", "lion"). {@link #find} also accepts what a model tends to write: "Brand Docker", "docker",
 * "laptop", "node.js". Licences are in resources/icons/ATTRIBUTION.md.
 */
public final class IconLibrary {

    private IconLibrary() { }

    public record Info(String name, boolean colour, String group, List<String> tags) { }

    private static volatile Map<String, Info> line, emoji;
    private static final Map<String, BufferedImage> IMAGES = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> TINTED = new ConcurrentHashMap<>();

    /** What a model or a person calls things -> the library name. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    static {
        String[][] a = {
            {"laptop", "device_laptop"}, {"computer", "device_desktop"}, {"desktop", "device_desktop"}, {"pc", "device_desktop"}, {"mobile", "device_mobile"},
            {"smartphone", "device_mobile"}, {"tablet", "device_tablet"}, {"tv", "device_tv"}, {"watch", "device_watch"}, {"gamepad", "device_gamepad_2"},
            {"docker", "brand_docker"}, {"node_js", "brand_nodejs"}, {"react_js", "brand_react"}, {"vue_js", "brand_vue"}, {"next_js", "brand_nodejs"}, {"html5", "brand_html5"}, {"css3", "brand_css3"}, {"github", "brand_github"}, {"python", "brand_python"}, {"javascript", "brand_javascript"}, {"js", "brand_javascript"},
            {"react", "brand_react"}, {"node", "brand_nodejs"}, {"nodejs", "brand_nodejs"}, {"html", "brand_html5"}, {"css", "brand_css3"}, {"aws", "brand_aws"},
            {"azure", "brand_azure"}, {"google", "brand_google"}, {"android", "brand_android"}, {"ios", "brand_apple"}, {"windows", "brand_windows"},
            {"vue", "brand_vue"}, {"angular", "brand_angular"}, {"typescript", "brand_typescript"}, {"kotlin", "brand_kotlin"}, {"swift", "brand_swift"},
            {"php", "brand_php"}, {"mongodb", "brand_mongodb"}, {"mysql", "brand_mysql"}, {"youtube", "brand_youtube"}, {"instagram", "brand_instagram"},
            {"whatsapp", "brand_whatsapp"}, {"telegram", "brand_telegram"}, {"linkedin", "brand_linkedin"}, {"linux", "terminal_2"}, {"shell", "terminal_2"},
            {"firewall", "shield_lock"}, {"load_balancer", "arrows_shuffle"}, {"queue", "stack_2"}, {"cache", "memory"}, {"microservice", "cube"},
            {"container", "packages"}, {"kubernetes", "topology_star_3"}, {"ci", "refresh"}, {"pipeline", "git_merge"}, {"encryption", "lock"},
            {"ssl", "certificate"}, {"tls", "certificate"}, {"https", "shield_check"}, {"dns", "world_www"}, {"cdn", "cloud_computing"}, {"gpu", "cpu_2"},
            {"ram", "memory"}, {"storage", "database"}, {"backup", "cloud_upload"}, {"download", "cloud_download"}, {"upload", "cloud_upload"},
            {"experiment", "flask"}, {"lab", "flask"}, {"chemistry", "flask"}, {"physics", "atom"}, {"biology", "dna"}, {"maths", "math_function"},
            {"math", "math_function"}, {"formula", "math_function"}, {"equation", "math_function"}, {"graph", "chart_line"}, {"statistics", "chart_histogram"},
            {"profit", "trending_up"}, {"growth", "trending_up"}, {"loss", "trending_down"}, {"money", "cash"}, {"rupee", "currency_rupee"},
            {"dollar", "currency_dollar"}, {"euro", "currency_euro"}, {"bitcoin", "currency_bitcoin"}, {"teacher", "presentation"}, {"student", "school"},
            {"exam", "checklist"}, {"quiz", "help_circle"}, {"idea", "bulb"}, {"light_bulb", "bulb"}, {"lightbulb", "bulb"}, {"warning", "alert_triangle"},
            {"error", "x"}, {"success", "circle_check"}, {"next", "arrow_right"}, {"back", "arrow_left"}, {"play", "player_play"}, {"pause", "player_pause"},
            {"stop", "player_stop"}, {"tree_diagram", "hierarchy_2"}, {"hierarchy", "hierarchy_2"}, {"org_chart", "hierarchy_2"}, {"deploy", "rocket"},
            {"launch", "rocket"}, {"bug_fix", "bug"}, {"test", "flask_2"}, {"database_server", "server_2"}, {"web", "world_www"}, {"internet", "world_www"},
            {"website", "world_www"}, {"email", "mail"}, {"chat_bubble", "message"}, {"notification", "bell"}, {"cow", "cow"}, {"elephant", "elephant"}
        };
        for (String[] p : a) ALIASES.put(p[0], p[1]);
    }

    private static synchronized void load() {
        if (line != null) return;
        Map<String, Info> l = new LinkedHashMap<>(), e = new LinkedHashMap<>();
        try (InputStream in = IconLibrary.class.getClassLoader().getResourceAsStream("icons/manifest.json")) {
            if (in != null) {
                JsonNode root = new ObjectMapper().readTree(in);
                for (String kind : List.of("line", "emoji")) {
                    var it = root.path(kind).fields();
                    while (it.hasNext()) {
                        var f = it.next();
                        List<String> tags = new ArrayList<>();
                        f.getValue().path("tags").forEach(t -> tags.add(t.asText().toLowerCase(Locale.ROOT)));
                        (kind.equals("line") ? l : e).put(f.getKey(), new Info(f.getKey(), kind.equals("emoji"), f.getValue().path("group").asText(""), tags));
                    }
                }
            }
        } catch (IOException ex) {
            // no library: every name falls back to the hand-drawn icons
        }
        emoji = e;
        line = l;
    }

    public static Map<String, Info> lineIcons() { load(); return line; }
    public static Map<String, Info> emojiIcons() { load(); return emoji; }

    /** "Brand Docker", "brand-docker", "Node.js" -> brand_docker, brand_docker, node_js */
    static String norm(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * The library name for what was asked, or null when the library has nothing sensible (the hand-drawn icons or a plain box then).
     * @param preferColour kids themes: a colourful emoji is chosen before a line icon of the same name
     */
    public static String find(String raw, boolean preferColour) {
        load();
        String n = norm(raw);
        if (n.isEmpty()) return null;
        if (n.startsWith("e_") && emoji.containsKey(n.substring(2))) return n;            // explicit emoji: "e_lion"
        String alias = ALIASES.get(n);
        if (preferColour) {                                              // kids: the colourful picture first
            if (emoji.containsKey(n)) return "e_" + n;
            if (line.containsKey(n)) return n;
        } else {                                                         // everything else: line icons first, and a known alias beats an emoji of the same word
            if (line.containsKey(n)) return n;
            if (alias != null && line.containsKey(alias)) return alias;
            if (emoji.containsKey(n)) return "e_" + n;
        }
        if (alias != null) {
            if (line.containsKey(alias)) return alias;
            if (emoji.containsKey(alias)) return "e_" + alias;
        }
        for (String prefix : new String[]{"brand_", "device_"}) if (line.containsKey(prefix + n)) return prefix + n;
        for (Info i : (preferColour ? emoji : line).values()) if (i.tags().contains(n.replace('_', ' '))) return (i.colour() ? "e_" : "") + i.name();
        for (Info i : (preferColour ? line : emoji).values()) if (i.tags().contains(n.replace('_', ' '))) return (i.colour() ? "e_" : "") + i.name();
        return null;
    }

    public static boolean isColour(String canonical) { return canonical != null && canonical.startsWith("e_"); }

    /** The white-on-transparent line icon or the colour emoji. */
    public static BufferedImage image(String canonical) {
        return IMAGES.computeIfAbsent(canonical, c -> {
            boolean colour = isColour(c);
            String path = "icons/" + (colour ? "emoji/" + c.substring(2) : "line/" + c) + ".png";
            try (InputStream in = IconLibrary.class.getClassLoader().getResourceAsStream(path)) {
                return in == null ? null : ImageIO.read(in);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** A line icon painted in one colour (cached per colour). */
    public static BufferedImage tinted(String canonical, Color colour) {
        String key = canonical + "#" + Integer.toHexString(colour.getRGB());
        BufferedImage cached = TINTED.get(key);
        if (cached != null) return cached;
        BufferedImage mask = image(canonical);
        if (mask == null) return null;
        BufferedImage out = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(mask, 0, 0, null);
        g.setComposite(AlphaComposite.SrcIn);
        g.setColor(colour);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.dispose();
        if (TINTED.size() > 800) TINTED.clear();       // bounded: a handful of themes x a few hundred icons in practice
        TINTED.put(key, out);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ for the planner

    /** Planner profiles: which groups a lesson of this kind should know about. */
    private static final Map<String, List<String>> PROFILE_LINE = Map.of(
            "TECH", List.of("tech", "nav", "business"), "SCIENCE", List.of("science", "maths", "nav"), "MATHS", List.of("maths", "nav", "education"),
            "BUSINESS", List.of("business", "nav", "tech"), "GK", List.of("education", "business", "transport", "nav"),
            "KIDS", List.of("nav"), "GENERAL", List.of("tech", "science", "education", "nav"));
    private static final Map<String, List<String>> PROFILE_EMOJI = Map.of(
            "TECH", List.of("objects"), "SCIENCE", List.of("nature", "objects"), "MATHS", List.of("shapes", "objects"), "BUSINESS", List.of("objects"),
            "GK", List.of("places", "nature", "animals", "objects", "vehicles"),
            "KIDS", List.of("animals", "food", "vehicles", "nature", "shapes", "objects", "places"), "GENERAL", List.of("nature", "objects"));

    /**
     * A compact list of icon names for the planner prompt ("use these exact names in any icon field"). Brand logos are limited to the
     * common ones so the list stays short. Emoji are written with the e_ prefix so a colourful picture can be asked for explicitly.
     */
    public static String catalog(String profile) {
        load();
        String p = PROFILE_LINE.containsKey(profile) ? profile : "GENERAL";
        List<String> names = new ArrayList<>();
        int brands = 0;
        for (String g : PROFILE_LINE.get(p)) {
            for (Info i : line.values()) {
                if (!i.group().equals(g)) continue;
                if (i.name().startsWith("brand_") && ++brands > 14) continue;
                names.add(i.name());
            }
        }
        for (String g : PROFILE_EMOJI.get(p)) for (Info i : emoji.values()) if (i.group().equals(g)) names.add("e_" + i.name());
        return "ICON NAMES (use exactly these in any \"icon\" field; a name that is not here becomes a plain box): "
                + String.join(", ", names) + ". Names starting with e_ are colourful pictures (best for children and general knowledge); "
                + "the rest are line icons in the lesson's colours.";
    }
}
