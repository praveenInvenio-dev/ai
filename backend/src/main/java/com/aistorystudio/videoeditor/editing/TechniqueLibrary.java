package com.aistorystudio.videoeditor.editing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Loads the editing technique and template definitions from
 * resources/video-editor at startup.
 *
 * <h2>Why techniques are data, not code</h2>
 *
 * Adding a transition should be a JSON file, not a rewrite. More importantly,
 * a technique's {@code render.kind} is a fixed enum the renderer switches on -
 * there is no free-text FFmpeg field anywhere in the schema. That is what makes
 * the "the LLM can never emit a filter string" guarantee structural rather than
 * a convention someone can accidentally break: the planner emits technique IDs,
 * an ID absent from this library fails validation, and a present one resolves
 * to a known kind.
 */
@Component
public class TechniqueLibrary {

    private static final Logger log = LoggerFactory.getLogger(TechniqueLibrary.class);

    /** The only render operations that exist. Anything else is unrepresentable. */
    public enum RenderKindEnum {
        HARD_CUT, XFADE, ZOOM, SPEED, AUDIO_LEAD, AUDIO_TAIL
    }

    public record Technique(
            String id,
            String name,
            String category,
            String purpose,
            double minSourceDuration,
            String intensity,
            List<String> requires,
            List<String> incompatibleWith,
            RenderKindEnum renderKind,
            String xfadeTransition,
            Map<String, Double> defaults,
            Map<String, double[]> ranges) {

        /** Clamps a parameter to its declared range, falling back to the default. */
        public double param(String key, Double requested) {
            double value = requested != null ? requested : defaults.getOrDefault(key, 0.0);
            double[] range = ranges.get(key);
            if (range == null) {
                return value;
            }
            return Math.max(range[0], Math.min(range[1], value));
        }
    }

    public record Template(
            String id,
            String category,
            String defaultAspectRatio,
            double minShotSeconds,
            double maxShotSeconds,
            List<String> preferredTechniques,
            List<String> avoidTechniques,
            String colorGrade,
            String notes) {}

    private final Map<String, Technique> techniques = new LinkedHashMap<>();
    private final Map<String, Template> templatesByCategory = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    void load() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();

        for (Resource r : resolver.getResources("classpath:video-editor/techniques/*.json")) {
            try (InputStream in = r.getInputStream()) {
                Technique t = parseTechnique(mapper.readTree(in));
                techniques.put(t.id(), t);
            }
        }
        for (Resource r : resolver.getResources("classpath:video-editor/templates/*.json")) {
            try (InputStream in = r.getInputStream()) {
                Template t = parseTemplate(mapper.readTree(in));
                templatesByCategory.put(t.category(), t);
            }
        }

        if (techniques.isEmpty()) {
            // Not a warning: with no techniques the planner cannot produce a
            // timeline at all, so failing loudly at startup beats every edit
            // silently coming out as unbroken hard cuts.
            throw new IllegalStateException(
                    "No editing techniques found on the classpath (video-editor/techniques/*.json)");
        }
        log.info("Loaded {} editing techniques and {} templates",
                techniques.size(), templatesByCategory.size());
    }

    private Technique parseTechnique(JsonNode n) {
        JsonNode render = n.path("render");
        RenderKindEnum kind = RenderKindEnum.valueOf(render.path("kind").asText("HARD_CUT"));

        Map<String, Double> defaults = new LinkedHashMap<>();
        Map<String, double[]> ranges = new LinkedHashMap<>();
        JsonNode params = n.path("parameters");
        params.fieldNames().forEachRemaining(key -> {
            JsonNode p = params.path(key);
            defaults.put(key, p.path("default").asDouble(0));
            ranges.put(key, new double[]{
                    p.path("min").asDouble(Double.NEGATIVE_INFINITY),
                    p.path("max").asDouble(Double.POSITIVE_INFINITY)});
        });

        return new Technique(
                n.path("id").asText(),
                n.path("name").asText(),
                n.path("category").asText(),
                n.path("purpose").asText(),
                n.path("minSourceDuration").asDouble(0),
                n.path("intensity").asText("balanced"),
                strings(n.path("requires")),
                strings(n.path("incompatibleWith")),
                kind,
                render.path("xfadeTransition").asText(null),
                defaults,
                ranges);
    }

    private Template parseTemplate(JsonNode n) {
        return new Template(
                n.path("id").asText(),
                n.path("category").asText(),
                n.path("defaultAspectRatio").asText("VERTICAL_9_16"),
                n.path("minShotSeconds").asDouble(1.5),
                n.path("maxShotSeconds").asDouble(6.0),
                strings(n.path("preferredTechniques")),
                strings(n.path("avoidTechniques")),
                n.path("colorGrade").asText("none"),
                n.path("notes").asText(""));
    }

    private List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return List.copyOf(out);
    }

    public Optional<Technique> find(String id) {
        return Optional.ofNullable(techniques.get(id));
    }

    public Collection<Technique> all() {
        return Collections.unmodifiableCollection(techniques.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(techniques.keySet());
    }

    /** Template for a category, falling back to CUSTOM's so an unmapped
     *  category still plans rather than failing. */
    public Template templateFor(String category) {
        Template t = templatesByCategory.get(category);
        if (t != null) {
            return t;
        }
        Template fallback = templatesByCategory.get("CUSTOM");
        if (fallback == null) {
            throw new IllegalStateException("No template for " + category + " and no CUSTOM fallback");
        }
        return fallback;
    }
}
