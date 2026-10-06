package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.*;
import com.aistorystudio.domain.Character;
import com.aistorystudio.dto.CreateStoryRequest;
import com.aistorystudio.dto.SceneDto;
import com.aistorystudio.dto.SceneDto.VoiceSegmentDto;
import com.aistorystudio.dto.StoryDraftResponse;
import com.aistorystudio.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aistorystudio.pipeline.promptbuilder.MotionPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The "creative director" of the studio. Turns a free-text idea + creative controls
 * into a structured Story Bible and scene breakdown via the local LLM, WITHOUT
 * touching any expensive image/audio providers. This is Stage A of the two-stage
 * workflow (see product spec section 2) - nothing here is expensive to redo.
 */
@Service
public class StoryEngineService {

    private static final Logger log = LoggerFactory.getLogger(StoryEngineService.class);

    private final MotionPromptBuilder motionPromptBuilder = new MotionPromptBuilder();
    @org.springframework.beans.factory.annotation.Value("${studio.tts.provider:piper}")
    private String ttsProvider;

    private final ProviderGateway providerGateway;
    private final EpisodeRepository episodeRepository;
    private final StoryBibleRepository storyBibleRepository;
    private final SceneRepository sceneRepository;
    private final CharacterRepository characterRepository;
    private final CharacterService characterService;
    private final EpisodeMemoryRepository episodeMemoryRepository;
    private final QualityEngineService qualityEngineService;
    private final ObjectMapper mapper = new ObjectMapper();

    public StoryEngineService(ProviderGateway providerGateway,
                               EpisodeRepository episodeRepository,
                               StoryBibleRepository storyBibleRepository,
                               SceneRepository sceneRepository,
                               CharacterRepository characterRepository,
                               CharacterService characterService,
                               EpisodeMemoryRepository episodeMemoryRepository,
                               QualityEngineService qualityEngineService) {
        this.providerGateway = providerGateway;
        this.episodeRepository = episodeRepository;
        this.storyBibleRepository = storyBibleRepository;
        this.sceneRepository = sceneRepository;
        this.characterRepository = characterRepository;
        this.characterService = characterService;
        this.episodeMemoryRepository = episodeMemoryRepository;
        this.qualityEngineService = qualityEngineService;
    }

    public StoryDraftResponse createDraft(CreateStoryRequest req) {
        Episode episode = new Episode();
        episode.setProjectId(req.projectId());
        episode.setUniverseId(req.universeId());
        episode.setUserPrompt(req.prompt());
        episode.setDurationTargetSec(req.durationSeconds() != null ? req.durationSeconds() : 180);
        episode.setTargetAge(req.targetAge());
        episode.setGenre(req.genre());
        episode.setTone(req.tone());
        episode.setVisualStyle(req.visualStyle());
        episode.setLanguage(normalizeStoryLanguage(req.language(), req.prompt()));
        episode.setOllamaModel(req.ollamaModel());
        if (req.qualityProfile() != null && !req.qualityProfile().isBlank()) {
            String upper = req.qualityProfile().trim().toUpperCase(java.util.Locale.ROOT);
            if (java.util.Set.of("FAST", "BALANCED", "QUALITY").contains(upper)) {
                episode.setQualityProfile(upper);
            }
            // Anything else silently keeps Episode's own BALANCED default -
            // a bad value here shouldn't block story creation over a
            // secondary generation-speed setting.
        }
        episode = episodeRepository.save(episode);

        return regenerateDraft(episode.getId(), req.characterIds());
    }

    public StoryDraftResponse regenerateDraft(UUID episodeId, List<UUID> characterIds) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));

        List<Character> characters = (characterIds == null || characterIds.isEmpty())
                ? List.of()
                : characterRepository.findAllById(characterIds);

        String continuityContext = buildContinuityContext(episode.getUniverseId());

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(episode, characters, continuityContext);

        String rawJson = providerGateway.llm().generateStructured(systemPrompt, userPrompt, episode.getOllamaModel());
        JsonNode root = parseJsonLeniently(rawJson);

        int nextVersion = storyBibleRepository.findByEpisodeIdOrderByVersionDesc(episodeId)
                .stream().mapToInt(StoryBible::getVersion).max().orElse(0) + 1;

        StoryBible bible = new StoryBible();
        bible.setEpisodeId(episodeId);
        bible.setVersion(nextVersion);
        bible.setTitle(text(root, "title"));
        bible.setLogline(text(root, "logline"));
        bible.setFullNarration(text(root, "fullNarration"));
        bible.setCharactersJson(jsonOrNull(root, "characters"));
        bible.setLocationsJson(jsonOrNull(root, "locations"));
        bible.setObjectsJson(jsonOrNull(root, "objects"));
        bible.setRelationshipsJson(jsonOrNull(root, "relationships"));
        bible.setTimelineJson(jsonOrNull(root, "timeline"));
        bible.setVisualStyleJson(enforceVisualStyleJson(root, episode));
        bible.setContinuityRulesJson(jsonOrNull(root, "continuityRules"));
        bible.setEmotionalArcJson(jsonOrNull(root, "emotionalArc"));
        bible.setStoryBeatsJson(jsonOrNull(root, "storyBeats"));
        bible = storyBibleRepository.save(bible);

        // Standalone stories still need real Character records so the Storyboard
        // Character Builder can generate, lock and reuse master references.
        // Universe-backed stories continue using their existing canonical characters.
        if (episode.getUniverseId() == null) {
            characterService.syncEpisodeCharacters(episode.getId(), root.path("characters"));
        }

        // remove previous scenes for this episode (a regenerate replaces the full draft;
        // production-time per-scene regeneration is handled separately once approved)
        sceneRepository.deleteAll(sceneRepository.findByEpisodeIdOrderByOrderIndexAsc(episodeId));

        List<Scene> scenes = new ArrayList<>();
        JsonNode sceneArray = root.path("scenes");
        int index = 0;
        double totalDuration = 0;
        for (JsonNode sceneNode : sceneArray) {
            Scene scene = new Scene();
            scene.setEpisodeId(episodeId);
            scene.setSceneNumber(index + 1);
            scene.setOrderIndex(index);
            scene.setPurpose(text(sceneNode, "purpose"));
            scene.setNarration(text(sceneNode, "narration"));
            scene.setCharactersJson(jsonOrNull(sceneNode, "characters"));
            scene.setVoiceSegmentsJson(buildVoiceSegmentsJson(sceneNode, text(sceneNode, "narration"), text(sceneNode, "emotion")));
            scene.setLocation(text(sceneNode, "location"));
            scene.setAction(text(sceneNode, "action"));
            scene.setEmotion(text(sceneNode, "emotion"));
            scene.setCamera(text(sceneNode, "camera"));
            scene.setLighting(text(sceneNode, "lighting"));
            scene.setVisualStyle(episode.getVisualStyle());
            scene.setImagePrompt(text(sceneNode, "imagePrompt"));
            scene.setNegativePrompt(text(sceneNode, "negativePrompt"));
            scene.setVisualSpecJson(jsonOrNull(sceneNode, "visualSpec"));
            scene.setAudioSpecJson(buildAudioSpecJson(sceneNode));
            // Motion prompt built from the scene fields set just above (action,
            // emotion, lighting, characters) - must come after those setters.
            scene.setContinuityJson(buildSceneContinuityJson(root, sceneNode, episode, characters));
            scene.setImportance(validateImportance(text(sceneNode, "importance")));
            scene.setCameraMovement(pickCameraMovement(index));
            scene.setTransitionIn(pickTransition(index, text(sceneNode, "emotion"), text(sceneNode, "purpose")));
            scene.setMotionPrompt(motionPromptBuilder.buildMotionPrompt(scene));
            scene.setMotionNegativePrompt(motionPromptBuilder.buildMotionNegativePrompt(scene));

            double narrationSeconds = estimateVoiceSegmentsSeconds(scene.getVoiceSegmentsJson(), scene.getNarration());
            scene.setNarrationSeconds(narrationSeconds);
            scene.setImageDurationSeconds(narrationSeconds + 0.75); // clean end beat
            totalDuration += scene.getImageDurationSeconds();

            scenes.add(scene);
            index++;
        }
        capHeroScenes(scenes);
        sceneRepository.saveAll(scenes);

        episode.setTitle(bible.getTitle());
        episode.setStatus(com.aistorystudio.domain.enums.EpisodeStatus.DRAFT_READY);
        episodeRepository.save(episode);

        var quality = qualityEngineService.evaluateDraft(bible, scenes, episode.getOllamaModel());
        episode.setQualityScore(quality.score());
        episodeRepository.save(episode);

        List<SceneDto> sceneDtos = scenes.stream().map(this::toDto).toList();

        return new StoryDraftResponse(
                episode.getId(), bible.getTitle(), bible.getLogline(), bible.getFullNarration(),
                quality.score(), quality.feedback(), sceneDtos, totalDuration);
    }

    private SceneDto toDto(Scene s) {
        return new SceneDto(s.getId(), s.getSceneNumber(), s.getPurpose(), s.getNarration(), s.getLocation(),
                s.getAction(), s.getEmotion(), s.getCamera(), s.getLighting(), s.getImagePrompt(),
                s.getNegativePrompt(), s.getMotionPrompt(), s.getMotionNegativePrompt(), s.getVisualSpecJson(), s.getAudioSpecJson(), s.getNarrationSeconds(), s.getImageDurationSeconds(), s.getCameraMovement(),
                s.getImportance(), s.isLocked(), s.isNarrationLocked(), s.getAnimationMode(), readVoiceSegments(s), readCharacterNames(s));
    }

    private List<VoiceSegmentDto> readVoiceSegments(Scene s) {
        if (s.getVoiceSegmentsJson() == null || s.getVoiceSegmentsJson().isBlank()) {
            return List.of(new VoiceSegmentDto("Narrator", s.getNarration(), "", 1.0, 1.0,
                    s.getEmotion() == null ? "neutral" : s.getEmotion(), 0, 0,
                    null, null, List.of(), null, null, null));
        }
        try {
            var arr = mapper.readTree(s.getVoiceSegmentsJson());
            List<VoiceSegmentDto> out = new ArrayList<>();
            if (arr.isArray()) for (JsonNode n : arr) {
                out.add(new VoiceSegmentDto(
                        text(n, "character"), text(n, "text"), text(n, "voice"),
                        n.path("speed").isNumber() ? n.get("speed").asDouble() : 1.0,
                        n.path("pitch").isNumber() ? n.get("pitch").asDouble() : 1.0,
                        text(n, "emotion"), n.path("pauseBeforeMs").asInt(0), n.path("pauseAfterMs").asInt(0),
                        n.path("emotionIntensity").isNumber() ? n.get("emotionIntensity").asDouble() : null,
                        text(n, "delivery"), readStringArray(n.path("emphasis")),
                        n.path("breath").isBoolean() ? n.get("breath").asBoolean() : null,
                        text(n, "paralinguisticEvent"), text(n, "actingDirection")));
            }
            return out.isEmpty() ? List.of() : out;
        } catch (Exception e) { return List.of(); }
    }

    private List<String> readStringArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n.isTextual()) out.add(n.asText());
        }
        return out;
    }

    private List<String> readCharacterNames(Scene s) {
        if (s.getCharactersJson() == null || s.getCharactersJson().isBlank()) return List.of();
        try {
            JsonNode arr = mapper.readTree(s.getCharactersJson());
            List<String> out = new ArrayList<>();
            if (arr.isArray()) for (JsonNode n : arr) {
                String name = n.isTextual() ? n.asText() : text(n, "name");
                if (name != null && !name.isBlank()) out.add(name);
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private String buildAudioSpecJson(JsonNode sceneNode) {
        JsonNode supplied = sceneNode.path("audioSpec");
        if (supplied.isObject()) return supplied.toString();
        ObjectNode out = mapper.createObjectNode();
        out.putArray("ambience");
        out.putObject("music").put("mood", text(sceneNode, "emotion") == null ? "gentle" : text(sceneNode, "emotion")).put("intensity", 0.25);
        out.putArray("sfx");
        out.put("dialoguePriority", 1.0);
        return out.toString();
    }

    private String buildVoiceSegmentsJson(JsonNode sceneNode, String narration, String sceneEmotion) {
        JsonNode supplied = sceneNode.path("voiceSegments");
        if (supplied.isArray() && supplied.size() > 0) return supplied.toString();
        ArrayNode out = mapper.createArrayNode();
        ObjectNode n = out.addObject();
        n.put("character", "Narrator");
        n.put("text", narration == null ? "" : narration);
        n.put("voice", ""); n.put("speed", 1.0); n.put("pitch", 1.0);
        n.put("emotion", sceneEmotion == null || sceneEmotion.isBlank() ? "neutral" : sceneEmotion);
        n.put("pauseBeforeMs", 0); n.put("pauseAfterMs", 0);
        return out.toString();
    }

    private static final java.util.Set<String> VALID_IMPORTANCE = java.util.Set.of("NORMAL", "IMPORTANT", "HERO");

    /** Never trust the model's value verbatim: anything outside the three
     *  known levels (missing field, stray text, wrong case) falls back to
     *  NORMAL rather than being written to a column something downstream
     *  might branch on. */
    private String validateImportance(String raw) {
        if (raw == null) {
            return "NORMAL";
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return VALID_IMPORTANCE.contains(upper) ? upper : "NORMAL";
    }

    /**
     * A model that marks every scene HERO has defeated the entire point of
     * the classification (spec section 21: hero shots exist to concentrate
     * production budget on a few moments, not to describe how good the
     * story is). Caps HERO scenes to roughly one in six, keeping the
     * earliest-occurring ones and downgrading the rest to IMPORTANT rather
     * than NORMAL, since a downgraded hero candidate is still more likely to
     * matter than an untouched scene.
     */
    private void capHeroScenes(List<Scene> scenes) {
        int maxHero = Math.max(1, scenes.size() / 6);
        int seen = 0;
        for (Scene scene : scenes) {
            if (!"HERO".equals(scene.getImportance())) {
                continue;
            }
            seen++;
            if (seen > maxHero) {
                scene.setImportance("IMPORTANT");
            }
        }
    }

    private String pickTransition(int sceneIndex, String emotion, String purpose) {
        String text = ((emotion == null ? "" : emotion) + " " + (purpose == null ? "" : purpose)).toLowerCase(Locale.ROOT);
        if (text.contains("climax") || text.contains("reveal") || text.contains("magic")) return "circleopen";
        if (text.contains("sad") || text.contains("goodbye") || text.contains("emotional")) return "fade";
        if (sceneIndex > 0 && sceneIndex % 4 == 0) return "wiperight";
        return "fade";
    }

    private String pickCameraMovement(int sceneIndex) {
        String[] movements = {"zoom-in", "zoom-out", "pan-left", "pan-right", "diagonal"};
        return movements[sceneIndex % movements.length];
    }

    static double estimateNarrationSeconds(String narration) {
        if (narration == null || narration.isBlank()) return 3.0;
        int words = narration.trim().split("\\s+").length;
        return Math.max(2.0, (words / 150.0) * 60.0);
    }

    private double estimateVoiceSegmentsSeconds(String voiceSegmentsJson, String narrationFallback) {
        try {
            if (voiceSegmentsJson != null && !voiceSegmentsJson.isBlank()) {
                JsonNode segments = mapper.readTree(voiceSegmentsJson);
                if (segments.isArray() && segments.size() > 0) {
                    double total = 0.0;
                    for (JsonNode segment : segments) {
                        String text = segment.path("text").asText("").trim();
                        if (text.isBlank()) continue;
                        double speed = Math.max(0.75, Math.min(1.25, segment.path("speed").asDouble(1.0)));
                        int words = text.split("\\s+").length;
                        total += (words / 145.0) * 60.0 / speed;
                        total += Math.max(0, segment.path("pauseBeforeMs").asInt(0)) / 1000.0;
                        total += Math.max(0, segment.path("pauseAfterMs").asInt(0)) / 1000.0;
                    }
                    if (total > 0.0) return Math.max(2.0, total);
                }
            }
        } catch (Exception ignored) { }
        return estimateNarrationSeconds(narrationFallback);
    }

    private String buildContinuityContext(UUID universeId) {
        if (universeId == null) return "This is a standalone story (no universe/continuity constraints).";
        List<EpisodeMemory> memories = episodeMemoryRepository.findByUniverseIdOrderByCreatedAtDesc(universeId);
        if (memories.isEmpty()) return "This is the first episode in this universe. No prior continuity yet.";
        // Only send the most recent compact memory, never full episode history (spec section 4).
        return "Prior episode memory (compact): " + memories.get(0).getSummaryJson();
    }

    private String normalizeStoryLanguage(String requested, String prompt) {
        if (requested == null || requested.isBlank()) return "English";
        String value = requested.trim();
        if (!value.equalsIgnoreCase("Auto-detect") && !value.equalsIgnoreCase("Auto")) return value;
        if (prompt != null) {
            for (int i = 0; i < prompt.length(); i++) {
                java.lang.Character.UnicodeScript script = java.lang.Character.UnicodeScript.of(prompt.charAt(i));
                switch (script) {
                    case KANNADA -> { return "Kannada"; }
                    case TELUGU -> { return "Telugu"; }
                    case TAMIL -> { return "Tamil"; }
                    case MALAYALAM -> { return "Malayalam"; }
                    case BENGALI -> { return "Bengali"; }
                    case GUJARATI -> { return "Gujarati"; }
                    case ORIYA -> { return "Odia"; }
                    case GURMUKHI -> { return "Punjabi"; }
                    case ARABIC -> { return "Urdu"; }
                    case DEVANAGARI -> { return "Hindi"; }
                    default -> { }
                }
            }
        }
        return "English";
    }

    private String buildSystemPrompt() {
        return """
            You are the creative director, head writer and short-form content strategist of an AI Story & Content Production Studio.
            You create original, age-appropriate children's/family stories designed to be genuinely
            entertaining, memorable and highly shareable without unsafe, deceptive or manipulative
            clickbait. The story must feel like a real story first, with viral-friendly pacing and
            visual moments naturally emerging from the plot.

            LANGUAGE IS A HARD REQUIREMENT. The requested story language is supplied in the user prompt.
            Write the title, logline, fullNarration, every narration segment, every dialogue segment,
            emotional wording and story-specific text in that requested language. Do NOT silently fall
            back to English. If the language is Kannada, write natural Kannada; Hindi, natural Hindi;
            Telugu, natural Telugu; Tamil, natural Tamil; Malayalam, natural Malayalam; Marathi, natural
            Marathi; Bengali, natural Bengali; Gujarati, natural Gujarati; Odia, natural Odia; Punjabi,
            natural Punjabi; Urdu, natural Urdu; Indian English, natural contemporary Indian English with authentic Indian phrasing and conversational rhythm (not British-English formality and not forced stereotypes). For Hinglish, use natural conversational Hindi-English
            code-switching rather than translating every English word. Preserve character names, proper
            nouns and unavoidable technical/production terms where appropriate. Do not write Indian
            language dialogue as awkward word-for-word translations. Use culturally natural expressions,
            child-friendly idioms and spoken phrasing for the target language.
            IMPORTANT: imagePrompt, negativePrompt, visualSpec, camera/lighting descriptions, audioSpec
            event labels and other machine-facing production instructions should remain concise,
            unambiguous production English so the image/video pipeline stays reliable.
            If the requested language is Auto-detect, infer it from the user's idea and use that language
            consistently for all story-facing text.
            MULTILINGUAL LEARNING LANGUAGE STYLE: For Entertainment Learning prompts, the user may request
            languageStyle=NATIVE, MIXED_TECH or MIXED_MODERN. NATIVE means natural target-language dialogue
            with only unavoidable technical proper nouns/code terms preserved. MIXED_TECH means the explanation
            and humour are native to the selected Indian language, while established technical terms such as
            Java, class, object, API, Kafka, Kubernetes, SQL, HTTP, CPU, RAM, algorithm, Article, section,
            formula names and code identifiers remain in natural English where speakers would normally use them.
            MIXED_MODERN means authentic conversational code-switching only where it sounds natural; do not
            alternate languages sentence-by-sentence mechanically. Never use English merely because the topic
            is technical. Preserve the requested language's grammar, idioms and cultural voice.

            Make the content highly engaging and retention-aware. Build a strong hook in the opening
            seconds: begin with an unusual event, intriguing question, funny surprise, emotional beat,
            mystery or visually striking situation rather than generic setup. Create an open question or
            curiosity gap early, then pay it off. Escalate the stakes through 2-4 meaningful surprises,
            reversals, discoveries or comedic complications instead of repeating the same action. Include
            at least one quotable/funny/emotional line and at least one visually spectacular or unexpected
            moment that could naturally become a thumbnail, Reel/Short clip or trailer moment. Keep scenes
            moving: alternate wide visual spectacle, character reaction, action, reveal and emotional payoff
            where appropriate. End with a satisfying payoff, memorable final image or small twist that makes
            the audience want another episode. Do not add artificial "like and subscribe" dialogue unless the
            user explicitly asks for it.

            Avoid filler, generic motivational speeches, repetitive moral lessons, predictable openings,
            random characters introduced only for spectacle, and twists that contradict established character
            behavior. Every surprise must grow from the story's characters, objects, location or setup.

            POLITICAL / CURRENT-AFFAIRS SATIRE MODE:
            When the genre or user idea asks for political satire, current-affairs satire, news parody,
            social commentary, or a "roast everyone" format, switch to mature satirical writing rather than
            children's-story conventions. The default target is the SYSTEM around the controversy: activists,
            political parties, government spokespeople, bureaucracy, media, influencers, online commenters and
            ordinary citizens can all be satirical targets. Do not automatically make one faction the hero.
            Roast opposing sides with comparable comedic intensity when the premise supports it. Use fictional
            names, fictional institutions and invented dialogue unless the user explicitly asks for a factual
            commentary segment. Never invent a real person's quote, action, arrest, admission, statistic or
            accusation and present it as fact. If a real current event is the inspiration, clearly transform it
            into fictional characters/situations and keep disputed allegations framed as allegations or satire.
            Do not create deceptive fake-news packaging that could reasonably be mistaken for an authentic news
            report. Do not target protected classes. Political satire may be sharp, irreverent, absurd and adult,
            but it should punch at power, hypocrisy, incentives, bureaucracy and public behaviour rather than
            dehumanize people. Prefer irony, parody, absurd escalation, deadpan reactions, mock press conferences,
            fictional apps/services, meme-like visual gags and a strong final punchline.

            For CURRENT HOT TOPIC prompts, build around the user's named issue and preserve the distinction
            between verified context and invented satire. A useful structure is: recognizable current setup,
            fictionalized escalation, roast side A, roast side B, ordinary-person perspective, escalating absurdity,
            then a balanced punchline that exposes the underlying contradiction. The audience should understand
            that it is satire, not breaking news.
            For younger children, keep tension exciting but emotionally safe and resolve frightening moments
            with reassurance. Follow this structure flexibly: Hook, Setup, Curiosity Gap, Escalation,
            Discovery, Complication, Climax, Emotional Payoff, Memorable Ending.
            Respond with ONLY valid JSON (no markdown fences, no commentary)
            matching this shape:
            {
              "title": string,
              "logline": string,
              "fullNarration": string,
              "characters": [ {"name": string, "role": string, "species": string, "age": string,
                "personality": string, "canonicalDescription": string, "negativeConstraints": string,
                "attributes": {"body": string, "face": string, "eyes": string, "hair": string,
                  "clothing": string, "colors": string, "accessories": string, "signatureFeatures": string} } ],
              "locations": [string],
              "objects": [string],
              "relationships": [string],
              "timeline": [string],
              "visualStyle": {"style": string, "colorPalette": string},
              "continuityRules": [string],
              "emotionalArc": [string],
              "storyBeats": [string],
              "scenes": [
                {
                  "purpose": string,
                  "narration": string,
                  "characters": [string],
                  "voiceSegments": [
                    {"character": string, "text": string, "voice": "", "speed": 1.0, "pitch": 1.0,
                     "emotion": string, "pauseBeforeMs": number, "pauseAfterMs": number,
                     "emotionIntensity": number, "delivery": string, "emphasis": [string],
                     "breath": boolean, "paralinguisticEvent": string, "actingDirection": string}
                  ],
                  "location": string,
                  "action": string,
                  "emotion": string,
                  "camera": string,
                  "lighting": string,
                  "imagePrompt": string,
                  "negativePrompt": string,
                  "audioSpec": {
                    "ambience": [string],
                    "music": {"mood": string, "intensity": number},
                    "sfx": [ {"event": string, "timing": string, "intensity": number} ],
                    "dialoguePriority": number
                  },
                  "visualSpec": {
                    "environment": {"location": string, "timeOfDay": string, "weather": string,
                      "season": string, "foreground": string, "midground": string, "background": string,
                      "atmosphere": string, "particles": string, "colorPalette": string, "mood": string},
                    "characters": [ {"name": string, "position": string, "pose": string,
                      "expression": string, "action": string} ],
                    "props": [string],
                    "lighting": {"keyLight": string, "fillLight": string, "rimLight": string,
                      "lightDirection": string, "shadows": string, "reflections": string},
                    "camera": {"position": string, "angle": string, "shotType": string, "lens": string,
                      "focalLength": string, "framing": string, "composition": string,
                      "depthOfField": string, "focusSubject": string, "movement": string},
                    "cinematicStyle": string,
                    "continuityRequirements": [string],
                    "environmentMotion": [string],
                    "visualEffects": [string]
                  },
                  "importance": "NORMAL" | "IMPORTANT" | "HERO"
                }
              ]
            }
            Choose the number of scenes based on pacing and story beats, not a fixed count -
            roughly one scene per 8-12 seconds of narration.
            Every scene MUST also contain voiceSegments. Use a Narrator segment for descriptive
            narration and separate character segments for spoken dialogue. Keep character names
            consistent with the characters array. Put natural pauses into pauseBeforeMs/pauseAfterMs
            (typically 150-800ms around emotional beats), and set emotion per line. Do not put
            dialogue into narration if it is also represented as a character voice segment.
            Every scene is a self-contained audiovisual beat. Each voice segment MUST finish
            naturally within the scene. Never split a sentence, thought, name, question, answer,
            or emotional reaction across scenes. The next scene must begin with a fresh complete
            line or narration beat, never a continuation of the previous sentence. If an exchange
            is too long for one scene, end at a natural speaker turn and continue with a new
            complete sentence in the next scene. Give the final segment enough pauseAfterMs for
            a clean audio tail before the visual cut.
            Break dialogue into SHORT natural segments rather than one long block per character per
            scene - "Bunny stopped." / "Whoa..." / "Is that a rainbow?" as three segments reads and
            performs far better than one run-on sentence. A segment can be a sentence fragment when
            that is how a real person would actually speak it.
            Fill emotionIntensity (0.0-1.0, how strongly the emotion should read - keep most lines in
            the 0.3-0.6 range; reserve 0.7+ for a genuine emotional peak, not every line) and delivery
            (a short label like "soft_excited", "flat_calm", "hushed_urgent" - how the line should be
            performed, not what it says). Set emphasis to the 0-2 words in the line that should land
            hardest, or omit it - not every line needs emphasis. Set breath true only where a real
            speaker would audibly breathe (after a long line, before a big reveal), not by default.
            paralinguisticEvent names a non-verbal sound this exact line calls for (e.g. "laugh",
            "gasp", "sigh") if any - leave it out otherwise; do not force one onto every line.
            actingDirection is one short sentence of real performance direction for this specific
            line (tone, pacing, where it starts/ends emotionally) - written for a voice actor, not a
            restatement of the emotion field.
            WRITE LIKE PERFORMED STORYTELLING, NOT WRITTEN PROSE. This applies to both the Narrator
            segment and every character line. Use contractions ("didn't", "it's", "let's") the way
            someone actually speaking would, not formal written English. Vary sentence length -
            short punchy lines for tension or excitement, longer ones for calm description. Use
            natural exclamations, rhetorical questions, and interjections where a real storyteller
            or a real child character would use them ("Wait... did you hear that?", "Oh no.",
            "Really?! Let's go!"). A flat, textbook-narration sentence for an exciting or emotional
            beat is a failure of the brief, not a stylistic choice - if a moment is exciting, scared,
            funny or sad, the WORDS THEMSELVES should carry that, not just the emotion field.
            """ + paralinguisticTagInstruction() + """
            The selected visual style is a HARD story-level production setting and is LOCKED across every scene.
            Treat the style name as a rendering contract, not a suggestion. For Anime, the imagePrompt MUST
            describe unmistakable Japanese 2D anime rendering (clean line art, cel shading, anime facial design,
            stylized anime proportions). For Cartoon, it MUST describe unmistakable 2D cartoon rendering. For
            3D Realistic, it MUST describe cinematic lifelike 3D with realistic materials and lighting. Never
            substitute a generic "beautiful illustration" phrase for the selected style. Never switch rendering
            style, character proportions, costume colors, facial features, species, age, or environment language
            between scenes. Reuse the exact same visual traits for recurring characters and locations.
            Every imagePrompt MUST be a concrete visual description of THAT scene's action, characters, location,
            objects and composition. Do not invent unrelated characters or animals. Do not omit named story objects
            that are part of the action. The imagePrompt should never introduce a conflicting art style.
            ALSO fill audioSpec for every scene. Keep ambience, music mood/intensity and only the sound effects that actually happen on screen; dialoguePriority should normally be 0.8-1.0.
            ALSO fill visualSpec for every scene - a structured cinematic breakdown, not a duplicate of imagePrompt.
            Think like a cinematographer: environment (location, time of day, weather, season, what's in the
            foreground/midground/background, atmosphere, particles, color palette, mood), where each character is
            positioned/posed/expressing/doing, lighting (key/fill/rim light, direction, shadows, reflections), and
            camera (position, angle, shot type, lens, framing, composition, depth of field, focus subject,
            movement). Be specific and concrete, not generic - "warm morning sunlight enters from the upper-left
            through tree branches, creating soft volumetric rays" not "nice lighting". visualSpec.characters MUST
            NEVER change a character's identity, face, body proportions, clothing, colors, accessories, or
            signature features - only their position, pose, expression and action in THIS scene. Leave any
            visualSpec field null/omitted rather than guessing when a scene genuinely doesn't call for it (e.g. no
            particles in a plain indoor scene) - do not pad every field just because the schema has it.
            Set importance per scene: HERO for the single biggest moment (the climax, a magical
            reveal, or the emotional peak - usually just one or two scenes per story), IMPORTANT
            for the opening hook, a major turning point, or the ending, and NORMAL for everything
            else (setup, transitions, ordinary action). Most scenes should be NORMAL - reserve HERO
            for what truly deserves the most production attention.
            """;
    }

    /** Only emitted when the configured TTS engine actually understands
     *  bracket tags (ChatterBox Turbo's native paralinguistic tags - see
     *  ChatterboxTTSProvider, which passes narration text straight through
     *  unmodified). Piper/Edge/Sarvam would speak a literal "[laugh]" as
     *  words, which is worse than not having the tag at all, so this must
     *  stay conditional on the provider rather than a blanket instruction. */
    private String paralinguisticTagInstruction() {
        if (!"chatterbox".equalsIgnoreCase(ttsProvider)) {
            return "";
        }
        return """
            The configured narration engine supports inline paralinguistic tags. Where a real
            performance would include one, add ONE of these tags directly in the narration or
            character line text, in brackets, at the natural point it would occur: [laugh],
            [chuckle], [gasp], [sigh], [cough]. Use them sparingly - only where the moment
            genuinely calls for it, never more than one or two per scene, and never on a line
            that doesn't need one just to use the feature.
            """;
    }

    private String buildUserPrompt(Episode episode, List<Character> characters, String continuityContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("User idea: ").append(episode.getUserPrompt()).append("\n");
        sb.append("Target duration (seconds): ").append(episode.getDurationTargetSec()).append("\n");
        if (episode.getTargetAge() != null) sb.append("Target age: ").append(episode.getTargetAge()).append("\n");
        if (episode.getGenre() != null) sb.append("Genre: ").append(episode.getGenre()).append("\n");
        if (episode.getTone() != null) sb.append("Tone: ").append(episode.getTone()).append("\n");
        if (episode.getVisualStyle() != null) sb.append("Visual style: ").append(episode.getVisualStyle()).append("\n");
        sb.append("Story language (HARD REQUIREMENT): ").append(episode.getLanguage()).append("\n");
        sb.append("Language rule: all story-facing text (title, logline, narration and dialogue) must be written naturally in the requested language; keep machine-facing image/video production fields in English.\n");
        sb.append("Content goal: maximize genuine viewer retention and shareability through a strong opening hook, curiosity gap, escalating surprises, emotional/comedic payoff, memorable lines and visually distinctive moments. Do not use deceptive clickbait.\n");
        String genre = episode.getGenre() == null ? "" : episode.getGenre().toLowerCase(Locale.ROOT);
        String idea = episode.getUserPrompt() == null ? "" : episode.getUserPrompt().toLowerCase(Locale.ROOT);
        boolean satireMode = genre.contains("satire") || genre.contains("parody") || genre.contains("commentary")
                || genre.contains("political") || idea.contains("satire") || idea.contains("roast everyone")
                || idea.contains("current affairs") || idea.contains("news parody");
        boolean learningMode = genre.contains("entertainment learning") || idea.contains("[entertainment_learning]") || idea.contains("teach") && (genre.contains("educational") || genre.contains("learning"));
        if (learningMode) {
            sb.append("ENTERTAINMENT LEARNING MODE: Teach the requested topic through an entertaining story, never as a lecture.\n");
            sb.append("Open with a concrete conflict, surprise, joke, mystery or absurd situation. Do NOT say 'today we will learn' or begin with a textbook definition.\n");
            sb.append("The technical/factual explanation must emerge naturally from character actions and dialogue. End with a memorable punchline or reveal that makes the concept stick.\n");
            sb.append("Use this hidden structure: hook -> entertaining problem -> metaphor/action -> accurate concept reveal -> concrete example -> punchline -> optional 1-2 second takeaway.\n");
            sb.append("Do not sacrifice factual accuracy for a joke. Never invent formulas, legal provisions, medical facts, programming behaviour, exam rules, dates, statistics or definitions.\n");
            sb.append("For programming topics, explain the actual language/runtime behaviour and include a tiny correct code example naturally in dialogue/visuals when useful.\n");
            sb.append("For medical topics, provide educational information only; do not diagnose, prescribe, or imply that a fictional character's symptoms prove a condition.\n");
            sb.append("For law/Constitution topics, identify the correct current legal framework and distinguish historical IPC/CrPC/IEA references from current BNS/BNSS/BSA where relevant; do not fabricate sections or case holdings.\n");
            sb.append("For UPSC/NEET/JEE and other exams, prioritize syllabus-relevant concepts and reasoning. Never invent current exam rules, dates, eligibility, syllabus or scoring claims.\n");
            sb.append("For construction/civil topics, keep safety-critical instructions conservative and do not turn dangerous site practices into actionable unsafe advice.\n");
            sb.append("Difficulty should be reflected in the depth, not by making the story less entertaining. Fit the explanation to the requested duration; for 30/60/90 second videos, prioritize one concept over breadth.\n");
            sb.append("MULTILINGUAL QUALITY CHECK: Before returning JSON, silently verify that title, logline, narration and dialogue consistently follow the requested language and languageStyle. Do not mix scripts or languages accidentally. If the target is an Indian language, prefer native spoken phrasing over literal translation. Technical terms may remain English only when allowed by MIXED_TECH or MIXED_MODERN, or when they are proper names/code identifiers that should not be translated.\n");
        }
        if (satireMode) {
            sb.append("SATIRE MODE: mature balanced political/current-affairs satire.\n");
            sb.append("Satirical target balance: do not automatically side with protesters, government, opposition, media, influencers or institutions.\n");
            sb.append("Use fictional characters/institutions and invented dialogue; never fabricate a real person's quote or factual event.\n");
            sb.append("Make the satire obvious through absurdity, irony, parody, fictional names or an explicit satire framing.\n");
            sb.append("If the idea names a live controversy, distinguish verified context from the invented comic plot.\n");
            sb.append("Build at least two strong roast beats aimed at different sides and finish with a punchline about the contradiction.\n");
        }
        if (!characters.isEmpty()) {
            sb.append("Existing canonical characters (do NOT change their appearance):\n");
            for (Character c : characters) {
                sb.append("- ").append(c.getName()).append(": ").append(c.getCanonicalDescription()).append("\n");
            }
        }
        sb.append(continuityContext).append("\n");
        return sb.toString();
    }

    private String enforceVisualStyleJson(JsonNode root, Episode episode) {
        ObjectNode visual = root.path("visualStyle").isObject()
                ? (ObjectNode) root.path("visualStyle").deepCopy()
                : mapper.createObjectNode();
        visual.put("style", episode.getVisualStyle() == null ? "3D Realistic" : episode.getVisualStyle());
        if (!visual.has("colorPalette") || visual.path("colorPalette").asText().isBlank()) {
            visual.put("colorPalette", defaultPalette(episode.getVisualStyle()));
        }
        return visual.toString();
    }

    private String defaultPalette(String visualStyle) {
        if (visualStyle == null) return "natural cinematic palette";
        return switch (visualStyle.trim().toUpperCase(Locale.ROOT)) {
            case "ANIME", "ANIME-INSPIRED" -> "clean anime palette, controlled highlights, harmonious saturated accents";
            case "CARTOON" -> "bright playful palette, readable primary accents, soft background colors";
            case "3D REALISTIC" -> "natural cinematic palette, realistic skin and environment tones";
            case "3D ANIMATED FEATURE" -> "warm family-animation palette, rich but controlled colors";
            case "STORYBOOK" -> "warm paper and watercolor palette, gentle natural accents";
            default -> "natural cinematic palette";
        };
    }

    private String buildSceneContinuityJson(JsonNode root, JsonNode sceneNode, Episode episode, List<Character> existingCharacters) {
        ObjectNode out = mapper.createObjectNode();
        out.put("visualStyle", episode.getVisualStyle() == null ? "3D Realistic" : episode.getVisualStyle());
        JsonNode visual = root.path("visualStyle");
        if (visual.isObject() && visual.has("colorPalette")) {
            out.put("colorPalette", visual.path("colorPalette").asText());
        }
        out.put("locationContinuity", text(sceneNode, "location"));
        out.put("sceneIntent", text(sceneNode, "action"));
        ArrayNode chars = out.putArray("characterContinuity");
        JsonNode names = sceneNode.path("characters");
        if (names.isArray()) {
            for (JsonNode n : names) {
                String name = n.isTextual() ? n.asText() : text(n, "name");
                if (name == null || name.isBlank()) continue;
                for (Character c : existingCharacters) {
                    if (c.getName() != null && c.getName().equalsIgnoreCase(name)) {
                        chars.add(c.getName() + ": " + c.getCanonicalDescription());
                        break;
                    }
                }
                if (chars.size() == 0 || !chars.toString().toLowerCase().contains(name.toLowerCase())) {
                    chars.add(name + " (keep the exact appearance established in the story bible)");
                }
            }
        }
        ArrayNode objects = out.putArray("objectContinuity");
        JsonNode rootObjects = root.path("objects");
        if (rootObjects.isArray()) rootObjects.forEach(o -> objects.add(o.asText()));
        return out.toString();
    }

    private JsonNode parseJsonLeniently(String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n", "").replaceAll("```$", "");
            }
            return mapper.readTree(cleaned);
        } catch (Exception e) {
            log.error("LLM did not return valid JSON, raw response: {}", raw);
            throw new IllegalStateException("Story engine received malformed JSON from LLM", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String jsonOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.toString();
    }
}
