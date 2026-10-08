package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.Asset;
import com.aistorystudio.domain.Character;
import com.aistorystudio.domain.Episode;
import com.aistorystudio.domain.Scene;
import com.aistorystudio.domain.StoryBible;
import com.aistorystudio.domain.enums.AssetType;
import com.aistorystudio.repository.AssetRepository;
import com.aistorystudio.repository.CharacterRepository;
import com.aistorystudio.repository.EpisodeRepository;
import com.aistorystudio.repository.SceneRepository;
import com.aistorystudio.repository.StoryBibleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Same story, another language": copies an approved story (scenes + their approved images,
 * characters) into a NEW episode and translates every spoken line with the story LLM.
 * Images are reused (no GPU), so a Kannada story becomes a ready-to-produce Hindi / Telugu /
 * Tamil ... story in a minute; then Produce video (H3 or Indic TTS) as usual.
 */
@Service
public class StoryTranslationService {

    private static final Logger log = LoggerFactory.getLogger(StoryTranslationService.class);

    private final ProviderGateway gateway;
    private final EpisodeRepository episodes;
    private final SceneRepository scenes;
    private final AssetRepository assets;
    private final StoryBibleRepository bibles;
    private final CharacterRepository characters;
    private final ObjectMapper mapper = new ObjectMapper();

    public StoryTranslationService(ProviderGateway gateway, EpisodeRepository episodes, SceneRepository scenes,
                                   AssetRepository assets, StoryBibleRepository bibles, CharacterRepository characters) {
        this.gateway = gateway;
        this.episodes = episodes;
        this.scenes = scenes;
        this.assets = assets;
        this.bibles = bibles;
        this.characters = characters;
    }

    @Transactional
    public Episode translate(UUID episodeId, String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) throw new IllegalArgumentException("Pick a target language.");
        Episode src = episodes.findById(episodeId).orElseThrow(() -> new IllegalArgumentException("Story not found: " + episodeId));
        String target = targetLanguage.trim();
        if (target.equalsIgnoreCase(src.getLanguage())) throw new IllegalArgumentException("The story is already in " + target + ".");
        List<Scene> srcScenes = scenes.findByEpisodeIdOrderByOrderIndexAsc(episodeId);
        if (srcScenes.isEmpty()) throw new IllegalStateException("This story has no scenes.");

        Episode copy = new Episode();
        BeanUtils.copyProperties(src, copy, "id", "createdAt", "updatedAt", "qualityScore", "narratorVoiceProfileId");
        copy.setLanguage(target);
        copy.setTitle(translateShort(src.getTitle(), src.getLanguage(), target, src.getOllamaModel()) + " (" + target + ")");
        copy.setCreatedAt(Instant.now());
        copy.setUpdatedAt(Instant.now());
        copy = episodes.save(copy);

        StoryBible srcBible = bibles.findFirstByEpisodeIdAndActiveTrueOrderByVersionDesc(episodeId).orElse(null);
        if (srcBible != null) {
            StoryBible nb = new StoryBible();
            BeanUtils.copyProperties(srcBible, nb, "id", "episodeId", "createdAt", "version");
            nb.setEpisodeId(copy.getId());
            nb.setVersion(1);
            nb.setActive(true);
            nb.setTitle(copy.getTitle());
            nb.setCreatedAt(Instant.now());
            bibles.save(nb);
        }

        // Standalone stories own their characters (V21 episode scope); universe stories share them.
        if (src.getUniverseId() == null) {
            for (Character c : characters.findByEpisodeId(episodeId)) {
                Character nc = new Character();
                BeanUtils.copyProperties(c, nc, "id", "episodeId", "createdAt", "updatedAt");
                nc.setEpisodeId(copy.getId());
                characters.save(nc);
            }
        }

        int done = 0;
        for (Scene s : srcScenes) {
            Scene n = new Scene();
            BeanUtils.copyProperties(s, n, "id", "episodeId", "createdAt", "updatedAt",
                    "narrationSeconds", "imageDurationSeconds", "narrationLocked");
            n.setEpisodeId(copy.getId());
            translateScene(s, n, src.getLanguage(), target, src.getOllamaModel());
            n.setCreatedAt(Instant.now());
            n.setUpdatedAt(Instant.now());
            n = scenes.save(n);
            Asset image = assets.findFirstBySceneIdAndAssetTypeAndActiveTrueOrderByVersionDesc(s.getId(), AssetType.IMAGE).orElse(null);
            if (image != null) {
                Asset a = new Asset();
                BeanUtils.copyProperties(image, a, "id", "episodeId", "sceneId", "createdAt");
                a.setEpisodeId(copy.getId());
                a.setSceneId(n.getId());
                a.setFilePath(copyFile(image.getFilePath(), copy.getId(), n.getSceneNumber()));
                a.setActive(true);
                a.setVersion(1);
                a.setCreatedAt(Instant.now());
                assets.save(a); // own copy of the approved image, no regeneration
            }
            done++;
        }
        log.info("Story {} translated to {} as {} ({} scenes)", episodeId, target, copy.getId(), done);
        return copy;
    }

    /** Narration + voice segments of one scene in one LLM call; structure (speakers, pauses, delivery) kept. */
    private void translateScene(Scene src, Scene dst, String from, String to, String model) {
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("narration", src.getNarration() == null ? "" : src.getNarration());
            ArrayNode lines = req.putArray("lines");
            JsonNode segs = src.getVoiceSegmentsJson() == null ? null : mapper.readTree(src.getVoiceSegmentsJson());
            if (segs != null && segs.isArray()) for (JsonNode seg : segs) lines.add(seg.path("text").asText(""));
            String raw = gateway.llm().generateStructured(systemPrompt(from, to), req.toString(), model);
            JsonNode out = mapper.readTree(stripFences(raw));
            String narration = out.path("narration").asText("");
            if (!narration.isBlank()) dst.setNarration(narration);
            if (segs != null && segs.isArray()) {
                JsonNode translated = out.path("lines");
                ArrayNode newSegs = mapper.createArrayNode();
                for (int i = 0; i < segs.size(); i++) {
                    ObjectNode seg = ((ObjectNode) segs.get(i)).deepCopy();
                    String t = translated.isArray() && i < translated.size() ? translated.get(i).asText("") : "";
                    if (!t.isBlank()) seg.put("text", t);
                    // TTS voices are language specific; let the new language pick its own.
                    seg.remove("voice");
                    newSegs.add(seg);
                }
                dst.setVoiceSegmentsJson(mapper.writeValueAsString(newSegs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Translation of scene " + src.getSceneNumber() + " failed: " + e.getMessage(), e);
        }
    }

    /** Public helper (Studio dub tool): translate spoken lines, same count and order. */
    public List<String> translateLines(List<String> lines, String from, String to, String model) {
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("narration", "");
            ArrayNode arr = req.putArray("lines");
            lines.forEach(arr::add);
            JsonNode out = mapper.readTree(stripFences(gateway.llm().generateStructured(systemPrompt(from, to), req.toString(), model)));
            List<String> result = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String t = out.path("lines").isArray() && i < out.path("lines").size() ? out.path("lines").get(i).asText("") : "";
                result.add(t.isBlank() ? lines.get(i) : t);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Translation failed: " + e.getMessage(), e);
        }
    }

    /** The copy gets its own image file, so deleting the original story never breaks it. */
    private String copyFile(String path, UUID episodeId, int sceneNumber) {
        try {
            java.nio.file.Path src = java.nio.file.Path.of(path);
            if (!java.nio.file.Files.isRegularFile(src)) return path;
            String name = src.getFileName().toString();
            java.nio.file.Path dst = src.getParent().resolve("translated-" + episodeId + "-s" + sceneNumber + "-" + name);
            java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dst.toString();
        } catch (Exception e) {
            log.warn("Could not copy image {} for translated story: {}", path, e.getMessage());
            return path;
        }
    }

    private String translateShort(String text, String from, String to, String model) {
        if (text == null || text.isBlank()) return "Story";
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("narration", text);
            req.putArray("lines");
            String raw = gateway.llm().generateStructured(systemPrompt(from, to), req.toString(), model);
            String t = mapper.readTree(stripFences(raw)).path("narration").asText("");
            return t.isBlank() ? text : t;
        } catch (Exception e) {
            return text;
        }
    }

    private static String systemPrompt(String from, String to) {
        return "You are a professional children's-story and short-video translator and dubbing writer. "
                + "Translate from " + (from == null || from.isBlank() ? "the source language" : from) + " into natural, spoken " + to
                + " as a native " + to + " storyteller would say it aloud. Write in the native script of " + to
                + " (e.g. Devanagari for Hindi/Marathi, Kannada script for Kannada, Tamil script for Tamil). "
                + "Keep character names as names (transliterate them into the target script), keep meaning, emotion, humour and line order. "
                + "Keep each line about the same spoken length as the original so the timing still fits. "
                + "Write numbers as words. Keep bracketed cues like [laughs] unchanged. "
                + "Input JSON: {\"narration\": string, \"lines\": [string,...]}. "
                + "Return ONLY JSON with exactly the same shape and the same number of lines: {\"narration\": string, \"lines\": [string,...]}.";
    }

    private static String stripFences(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(json)?", "");
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }
}
