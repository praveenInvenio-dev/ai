package com.aistorystudio.h3;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.MediaProcessor;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.provider.VideoGenerationProvider;
import com.aistorystudio.sequence.ClipMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ONE way to turn a story scene into an H3 clip with H3-generated speech, used by every flow:
 * Video Generation (scene loaded), Story Video Production (sequence) and Storyboard / Story
 * Approval "Produce video".
 *
 * <pre>
 * scene lines -> clean text + resolve language (IndicSpeech)
 *             -> split into pieces that fit one shot (no mid-word cuts)
 *             -> group into shots:  narrator run = VOICE-OVER shot, character run = DIALOGUE shot
 *   DIALOGUE  : 1 H3 I2V pass, words in prompt -> voice + lip sync together
 *   VOICE-OVER: H3 audio-only pass with the words (narrator + ambience + music)
 *               + H3 I2V pass with NO words ("nobody talks") -> mux
 *   SILENT    : 1 H3 I2V pass, no speech
 *   shots chained by last frame -> concat -> loudness / 48 kHz polish
 * </pre>
 */
@Service
public class H3SceneRenderer {

    private static final Logger log = LoggerFactory.getLogger(H3SceneRenderer.class);
    private static final Pattern VOICE_OVER_SPEAKER = Pattern.compile(
            "^(narrator|narration|voice ?over|v\\.?o\\.?|storyteller|sutradhar|सूत्रधार|ನಿರೂಪಕ|கதைசொல்லி)$",
            Pattern.CASE_INSENSITIVE);
    private static final String NEGATIVE = "blurry, deformed face, identity drift, extra limbs, duplicate character, subtitles, captions, text, watermark, logo";

    public enum ShotKind { DIALOGUE, VOICE_OVER, SILENT }

    /**
     * Who speaks the lines. H3 = H3 generates the voice (default). INDIC_TTS = IndicF5 voices
     * (tts-indic) speak, H3 only animates (dialogue lip-synced to the TTS clip) and supplies
     * ambience/SFX/music, ducked under the voice. Either one or the other, never both.
     */
    public enum SpeechEngine {
        H3, INDIC_TTS;

        public static SpeechEngine parse(String v) {
            if (v == null || v.isBlank()) return H3;
            String u = v.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return u.startsWith("INDIC") || u.equals("TTS") ? INDIC_TTS : H3;
        }
    }

    /** One spoken line of the scene, in order. {@code voiceOver} = narrator, never lip-synced. */
    public record Line(String speaker, String text, String delivery, boolean voiceOver, double pauseSeconds, String voice) {
    }

    public record SceneSpec(String visualDirection, String location, String emotion, String visualStyle,
                            String language, List<Line> lines, String audioSpecJson, String audioDirection,
                            double silentSeconds, String voiceSeedKey, SpeechEngine speechEngine) {
    }

    /**
     * @param startImage first frame (null = text-to-video for the first shot)
     * @param width/height output size of the merged scene; 0 = keep the size H3 produced
     * @param cancelled   checked between shots
     */
    public record Options(Path startImage, int width, int height, Path workDir, BooleanSupplier cancelled,
                          Path characterReference) {
        public Options(Path startImage, int width, int height, Path workDir, BooleanSupplier cancelled) {
            this(startImage, width, height, workDir, cancelled, null);
        }
    }

    public record Result(Path video, double seconds, int shots, List<String> warnings) {
    }

    private record Piece(String speaker, String text, String delivery, double seconds, String languageTag, String voice) {
    }

    private record Shot(ShotKind kind, List<Piece> pieces, double seconds) {
    }

    /** Per-render state shared by every shot. */
    private record Run(H3ScenePrompts.SceneContext ctx, SpeechEngine engine, IndicSpeech.Language lang,
                       Map<String, String> voices, Path characterReference, long voiceSeed, Path work,
                       List<String> warnings) {
    }

    private final ProviderGateway providerGateway;
    private final MediaProcessor mediaProcessor;
    private final String indicNarratorVoice;
    private final boolean ttsLipSyncReference;
    private final ObjectMapper mapper = new ObjectMapper();
    private final double configuredShotSeconds;
    private final double speechTailSeconds;
    private final double musicIntensityCap;
    private final String narratorVoice;
    private final double loudnessTarget;
    private final boolean audioTurbo;
    private final boolean voiceOverTailRetry;

    public H3SceneRenderer(ProviderGateway providerGateway, MediaProcessor mediaProcessor,
                           @Value("${studio.h3.scene.indic-narrator-voice:}") String indicNarratorVoice,
                           @Value("${studio.h3.scene.tts-lipsync-reference:true}") boolean ttsLipSyncReference,
                           @Value("${studio.h3.scene.max-shot-seconds:${studio.animation.local-ai.minimax-h3-shot-seconds:8.0}}") double configuredShotSeconds,
                           @Value("${studio.h3.scene.speech-tail-seconds:0.8}") double speechTailSeconds,
                           @Value("${studio.h3.scene.music-intensity-cap:0.35}") double musicIntensityCap,
                           @Value("${studio.h3.scene.narrator-voice:warm, clear, mature storyteller voice with gentle expression}") String narratorVoice,
                           @Value("${studio.h3.scene.loudness-lufs:-16}") double loudnessTarget,
                           @Value("${studio.audio.h3.turbo:true}") boolean audioTurbo,
                           @Value("${studio.h3.scene.voice-over-tail-retry:true}") boolean voiceOverTailRetry) {
        this.providerGateway = providerGateway;
        this.mediaProcessor = mediaProcessor;
        this.indicNarratorVoice = indicNarratorVoice;
        this.ttsLipSyncReference = ttsLipSyncReference;
        this.configuredShotSeconds = configuredShotSeconds;
        this.speechTailSeconds = speechTailSeconds;
        this.musicIntensityCap = musicIntensityCap;
        this.narratorVoice = narratorVoice;
        this.loudnessTarget = loudnessTarget;
        this.audioTurbo = audioTurbo;
        this.voiceOverTailRetry = voiceOverTailRetry;
    }

    // ================================================================== public API

    public Result render(SceneSpec spec, Options opt) throws IOException {
        Files.createDirectories(opt.workDir());
        List<String> warnings = new ArrayList<>();
        String allText = spec.lines() == null ? "" : String.join(" ", spec.lines().stream()
                .map(Line::text).filter(t -> t != null && !t.isBlank()).toList());
        IndicSpeech.Language lang = IndicSpeech.resolve(spec.language(), allText);
        IndicSpeech.Language declared = IndicSpeech.resolve(spec.language(), "");
        if (spec.language() != null && !spec.language().isBlank() && !allText.isBlank()
                && !declared.name().equals(lang.name())) {
            warnings.add("Story language '" + spec.language() + "' does not match the text script; speaking as " + lang.tag() + ".");
        }

        double maxShot = maxShotSeconds();
        double maxSpeech = Math.max(2.0, maxShot - speechTailSeconds);
        boolean[] flags = new boolean[2]; // digits, latin words
        List<Shot> plan = plan(spec, lang, maxShot, maxSpeech, flags, warnings);
        if (lang.romanized()) warnings.add("Lines are " + lang.name() + " written in Latin letters; native script gives H3 clearer pronunciation.");

        H3ScenePrompts.SceneContext ctx = new H3ScenePrompts.SceneContext(
                spec.visualDirection(), spec.location(), spec.emotion(), spec.visualStyle(), lang,
                audioCues(spec.audioSpecJson(), spec.audioDirection()), musicIntensityCap, narratorVoice,
                flags[0], flags[1]);
        long voiceSeed = stableSeed(spec.voiceSeedKey());
        SpeechEngine engine = spec.speechEngine() == null ? SpeechEngine.H3 : spec.speechEngine();
        Map<String, String> voices = engine == SpeechEngine.INDIC_TTS ? assignIndicVoices(plan, lang) : Map.of();
        Run run = new Run(ctx, engine, lang, voices, opt.characterReference(), voiceSeed, opt.workDir(), warnings);
        log.info("H3 scene: speech engine {}{}", engine, voices.isEmpty() ? "" : " voices " + voices);

        List<Path> clips = new ArrayList<>();
        Path current = opt.startImage();
        int index = 0;
        for (Shot shot : plan) {
            if (opt.cancelled() != null && opt.cancelled().getAsBoolean()) {
                throw new IllegalStateException("Cancelled.");
            }
            List<Path> made = renderWithFallback(run, shot, index, current);
            for (Path clip : made) {
                clips.add(clip);
                index++;
                current = ClipMerger.extractLastFrame(clip, opt.workDir().resolve(String.format(Locale.ROOT, "last-%03d.png", index)));
            }
        }
        if (clips.isEmpty()) throw new IllegalStateException("H3 produced no shots for this scene.");

        int w = opt.width(), h = opt.height();
        if (w <= 0 || h <= 0) {
            int[] dims = probeSize(clips.get(0));
            w = even(dims[0]); h = even(dims[1]);
        }
        Path merged = opt.workDir().resolve("scene-merged.mp4");
        ClipMerger.merge(clips, merged, w, h, 0);
        Path polished = opt.workDir().resolve("scene-final.mp4");
        polishAudio(merged, polished);
        double seconds = ClipMerger.probeDuration(polished);
        for (String wmsg : warnings) log.warn("H3 scene: {}", wmsg);
        log.info("H3 scene rendered: {} shot(s), {}s, language={}, speech={}", clips.size(), fmt(seconds), lang.tag(), engine);
        return new Result(polished, seconds, clips.size(), List.copyOf(warnings));
    }

    /** Voice segments JSON (Story Engine format) -> ordered lines. Falls back to the narration. */
    public static List<Line> linesFromVoiceSegments(ObjectMapper mapper, String json, String fallbackNarration) {
        List<Line> out = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode arr = mapper.readTree(json);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String text = n.path("text").asText("");
                        if (text.isBlank()) continue;
                        String speaker = n.path("character").asText("").trim();
                        String delivery = firstNonBlank(n.path("actingDirection").asText(""), n.path("delivery").asText(""),
                                n.path("emotion").asText(""));
                        double pause = (n.path("pauseBeforeMs").asDouble(0) + n.path("pauseAfterMs").asDouble(0)) / 1000.0;
                        String voice = n.path("voice").asText("").trim();
                        out.add(new Line(speaker.isBlank() ? "Narrator" : speaker, text, delivery,
                                isVoiceOver(speaker), Math.max(0, Math.min(2.0, pause)), voice.isBlank() ? null : voice));
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read voice segments: {}", e.getMessage());
            }
        }
        if (out.isEmpty() && fallbackNarration != null && !fallbackNarration.isBlank()) {
            out.add(new Line("Narrator", fallbackNarration, null, true, 0, null));
        }
        return out;
    }

    /** Free text from the UI: narration (voice-over) first, then "Name: line [delivery]" dialogue lines. */
    public static List<Line> linesFromText(String narration, String dialogue) {
        List<Line> out = new ArrayList<>();
        if (narration != null && !narration.isBlank()) out.add(new Line("Narrator", narration, null, true, 0, null));
        if (dialogue != null && !dialogue.isBlank()) {
            Pattern p = Pattern.compile("^\\s*([^:]{1,40}):\\s*(.+?)\\s*(?:\\[([^\\]]{1,60})])?\\s*$");
            for (String row : dialogue.split("\\r?\\n")) {
                if (row.isBlank()) continue;
                Matcher m = p.matcher(row);
                if (m.matches()) {
                    String speaker = m.group(1).trim();
                    out.add(new Line(speaker, m.group(2), m.group(3), isVoiceOver(speaker), 0, null));
                } else {
                    out.add(new Line("Narrator", row.trim(), null, true, 0, null));
                }
            }
        }
        return out;
    }

    public static boolean isVoiceOver(String speaker) {
        return speaker == null || speaker.isBlank() || VOICE_OVER_SPEAKER.matcher(speaker.trim()).matches();
    }

    public double maxShotSeconds() {
        double providerMax = providerGateway.maxVideoDurationSecondsFor("minimax-h3-image-to-video");
        double max = configuredShotSeconds > 0 ? configuredShotSeconds : 8.0;
        if (providerMax > 0) max = Math.min(max, providerMax);
        return Math.max(3.0, max);
    }

    // ================================================================== planning

    private List<Shot> plan(SceneSpec spec, IndicSpeech.Language lang, double maxShot, double maxSpeech,
                            boolean[] flags, List<String> warnings) {
        List<Shot> shots = new ArrayList<>();
        List<Line> lines = spec.lines() == null ? List.of() : spec.lines();
        // 1. clean + split every line into pieces that fit one shot
        List<Piece> pieces = new ArrayList<>();
        List<Boolean> voiceOver = new ArrayList<>();
        for (Line line : lines) {
            if (line.text() == null || line.text().isBlank()) continue;
            IndicSpeech.Language lineLang = IndicSpeech.resolve(lang.name(), line.text());
            IndicSpeech.Prepared prepared = IndicSpeech.prepare(line.text(), lineLang);
            if (prepared.text().isBlank()) continue;
            flags[0] |= prepared.hasDigits();
            flags[1] |= prepared.hasLatinWords();
            String delivery = line.delivery();
            if (!prepared.cues().isEmpty()) {
                String cue = String.join(", ", prepared.cues());
                delivery = delivery == null || delivery.isBlank() ? cue : delivery + "; " + cue;
            }
            List<String> parts = IndicSpeech.split(prepared.text(), lineLang, maxSpeech);
            if (parts.size() > 1) {
                warnings.add("Line of " + line.speaker() + " split into " + parts.size() + " shots to fit H3 (" + fmt(maxShot) + "s per shot).");
            }
            for (int i = 0; i < parts.size(); i++) {
                double est = IndicSpeech.estimateSeconds(parts.get(i), lineLang) + (i == 0 ? line.pauseSeconds() : 0);
                pieces.add(new Piece(line.speaker(), parts.get(i), delivery, est, lineLang.tag(), line.voice()));
                voiceOver.add(line.voiceOver());
            }
        }
        if (pieces.isEmpty()) {
            double total = Math.max(2.0, spec.silentSeconds() > 0 ? spec.silentSeconds() : 5.0);
            int n = (int) Math.ceil(total / maxShot);
            for (int i = 0; i < n; i++) shots.add(new Shot(ShotKind.SILENT, List.of(), Math.max(2.0, total / n)));
            return shots;
        }
        // 2. group consecutive pieces of the same kind into shots
        List<Piece> group = new ArrayList<>();
        boolean groupVo = voiceOver.get(0);
        double groupSeconds = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            boolean vo = voiceOver.get(i);
            if (!group.isEmpty() && (vo != groupVo || groupSeconds + p.seconds() > maxSpeech)) {
                shots.add(shotOf(groupVo, group, groupSeconds, maxShot));
                group = new ArrayList<>();
                groupSeconds = 0;
            }
            group.add(p);
            groupVo = vo;
            groupSeconds += p.seconds();
        }
        shots.add(shotOf(groupVo, group, groupSeconds, maxShot));
        return shots;
    }

    private Shot shotOf(boolean voiceOver, List<Piece> pieces, double speechSeconds, double maxShot) {
        double seconds = Math.min(maxShot, Math.max(3.0, speechSeconds + speechTailSeconds));
        return new Shot(voiceOver ? ShotKind.VOICE_OVER : ShotKind.DIALOGUE, List.copyOf(pieces), seconds);
    }

    // ================================================================== rendering

    /** On GPU out-of-memory a multi-piece shot is split in two instead of cutting speech. */
    private List<Path> renderWithFallback(Run run, Shot shot, int index, Path startImage) throws IOException {
        try {
            return List.of(renderShot(run, shot, index, startImage));
        } catch (RuntimeException e) {
            if (!looksLikeOutOfMemory(e) || shot.pieces().size() < 2) throw e;
            run.warnings().add("H3 ran out of memory on shot " + (index + 1) + "; splitting it in two.");
            int mid = shot.pieces().size() / 2;
            List<Piece> a = shot.pieces().subList(0, mid);
            List<Piece> b = shot.pieces().subList(mid, shot.pieces().size());
            Shot first = new Shot(shot.kind(), List.copyOf(a), Math.max(3.0, sum(a) + speechTailSeconds));
            Shot second = new Shot(shot.kind(), List.copyOf(b), Math.max(3.0, sum(b) + speechTailSeconds));
            List<Path> out = new ArrayList<>(renderWithFallback(run, first, index, startImage));
            Path next = ClipMerger.extractLastFrame(out.get(out.size() - 1), run.work().resolve("oom-" + UUID.randomUUID() + ".png"));
            out.addAll(renderWithFallback(run, second, index + out.size(), next));
            return out;
        }
    }

    private Path renderShot(Run run, Shot shot, int index, Path startImage) throws IOException {
        boolean hasImage = startImage != null && Files.isRegularFile(startImage);
        String tag = String.format(Locale.ROOT, "shot-%03d", index);
        H3ScenePrompts.SceneContext ctx = run.ctx();
        log.info("H3 {} {}: {}s, {} piece(s), speech={}", tag, shot.kind(), fmt(shot.seconds()), shot.pieces().size(), run.engine());
        if (shot.kind() != ShotKind.SILENT && run.engine() == SpeechEngine.INDIC_TTS) {
            return renderTtsShot(run, shot, index, startImage, tag);
        }
        Path work = run.work();
        return switch (shot.kind()) {
            case SILENT -> writeVideo(work.resolve(tag + ".mp4"),
                    video(H3ScenePrompts.silentShot(ctx, shot.seconds(), index, hasImage), shot.seconds(), startImage, hasImage, null, null));
            case DIALOGUE -> writeVideo(work.resolve(tag + ".mp4"),
                    video(H3ScenePrompts.dialogueShot(ctx, spoken(shot, false), shot.seconds(), index, hasImage),
                            shot.seconds(), startImage, hasImage, null, null));
            case VOICE_OVER -> renderVoiceOver(run, shot, index, startImage, hasImage, tag);
        };
    }

    private Path renderVoiceOver(Run run, Shot shot, int index, Path startImage, boolean hasImage, String tag) throws IOException {
        H3ScenePrompts.SceneContext ctx = run.ctx();
        Path work = run.work();
        double seconds = shot.seconds();
        List<H3ScenePrompts.Spoken> lines = spoken(shot, true);
        // Audio first: its real length decides the video length.
        Path audio = narrationAudio(ctx, lines, seconds, run.voiceSeed(), work.resolve(tag + "-vo"));
        double maxShot = maxShotSeconds();
        if (voiceOverTailRetry && speechRunsToEnd(audio) && seconds + 1.0 <= maxShot + 0.01) {
            double longer = Math.min(maxShot, seconds + 1.5);
            run.warnings().add("Narration on shot " + (index + 1) + " was still speaking at the end; regenerated at " + fmt(longer) + "s.");
            seconds = longer;
            audio = narrationAudio(ctx, lines, seconds, run.voiceSeed(), work.resolve(tag + "-vo2"));
        }
        if (speechRunsToEnd(audio)) {
            run.warnings().add("Narration on shot " + (index + 1) + " may be cut at the end; shorten the line.");
        }
        double audioSeconds = ClipMerger.probeDuration(audio);
        Path silentVideo = writeVideo(work.resolve(tag + "-visual.mp4"),
                video(H3ScenePrompts.voiceOverVisualShot(ctx, Math.max(seconds, audioSeconds), index, hasImage),
                        Math.max(seconds, audioSeconds), startImage, hasImage, null, null));
        Path out = work.resolve(tag + ".mp4");
        muxVoiceOver(silentVideo, audio, out, audioSeconds);
        return out;
    }

    // ================================================================== Indic TTS speech

    /**
     * INDIC_TTS: the IndicF5 voice is recorded first and is the authoritative speech.
     * Voice-over: H3 animates with no words; its own ambience/music becomes the bed.
     * Dialogue: H3 animates with the TTS clip as reference audio (lip sync), and a separate
     * H3 audio-only pass makes a speech-free bed (the video pass's own audio contains H3 speech).
     */
    private Path renderTtsShot(Run run, Shot shot, int index, Path startImage, String tag) throws IOException {
        Path work = run.work();
        Path speech = ttsSpeech(run, shot, work.resolve(tag + "-tts.wav"));
        double speechSeconds = ClipMerger.probeDuration(speech);
        double total = Math.max(3.0, speechSeconds + speechTailSeconds);
        double maxShot = maxShotSeconds();
        int parts = (int) Math.ceil(total / maxShot - 1e-6);
        double per = total / Math.max(1, parts);
        boolean dialogue = shot.kind() == ShotKind.DIALOGUE;

        List<Path> visuals = new ArrayList<>();
        Path current = startImage;
        for (int i = 0; i < parts; i++) {
            boolean hasImage = current != null && Files.isRegularFile(current);
            String ptag = tag + "-p" + i;
            VideoGenerationProvider.VideoGenerationResult r;
            if (dialogue) {
                Path ref = null;
                if (ttsLipSyncReference) {
                    ref = work.resolve(ptag + "-ref.wav");
                    sliceAudio(speech, ref, i * per, Math.min(per, Math.max(0.5, speechSeconds - i * per)));
                }
                String prompt = ref != null
                        ? H3ScenePrompts.dialogueShotForTts(run.ctx(), spoken(shot, false), per, index + i, hasImage)
                        : H3ScenePrompts.dialogueShot(run.ctx(), spoken(shot, false), per, index + i, hasImage);
                // Character sheet only together with reference audio (the reference graph needs one of them).
                r = video(prompt, per, current, hasImage, ref, ref != null ? run.characterReference() : null);
            } else {
                r = video(H3ScenePrompts.voiceOverVisualShot(run.ctx(), per, index + i, hasImage), per, current, hasImage, null, null);
            }
            Path v = writeVideo(work.resolve(ptag + ".mp4"), r);
            visuals.add(v);
            if (i < parts - 1) current = ClipMerger.extractLastFrame(v, work.resolve(ptag + "-last.png"));
        }
        Path visual = visuals.get(0);
        if (visuals.size() > 1) {
            int[] dims = probeSize(visuals.get(0));
            visual = work.resolve(tag + "-visual.mp4");
            ClipMerger.merge(visuals, visual, even(dims[0]), even(dims[1]), 0);
        }
        Path bed = null;
        if (dialogue) {
            try {
                var sound = providerGateway.generateH3Audio(new VideoGenerationProvider.H3AudioRequest(
                        H3ScenePrompts.soundscapeOnly(run.ctx(), Math.min(total, maxShot)), Math.min(total, maxShot),
                        run.voiceSeed() + index, 0, audioTurbo));
                bed = work.resolve(tag + "-bed." + (sound.fileExtension() == null ? "wav" : sound.fileExtension()));
                Files.write(bed, sound.audioBytes());
            } catch (RuntimeException e) {
                run.warnings().add("Ambience bed for shot " + (index + 1) + " failed (" + e.getMessage() + "); voice only.");
            }
        }
        Path out = work.resolve(tag + ".mp4");
        mixVoiceOverBed(visual, speech, bed, !dialogue, out, total);
        return out;
    }

    /** Speaks every piece of the shot with its IndicF5 voice, humanizes it, joins with short gaps. */
    private Path ttsSpeech(Run run, Shot shot, Path out) throws IOException {
        List<Path> parts = new ArrayList<>();
        int i = 0;
        for (Piece p : shot.pieces()) {
            String voice = run.voices().get(p.speaker());
            var result = providerGateway.synthesizeIndicVoice(new TextToSpeechProvider.TtsRequest(
                    p.text(), voice, run.lang().name(), 1.0, 1.0, null, null, p.delivery(), List.of(), false, null, p.delivery()));
            byte[] wav = result.audioBytes();
            try {
                wav = mediaProcessor.humanizeVoice(wav);
            } catch (RuntimeException e) {
                log.debug("humanizeVoice skipped: {}", e.getMessage());
            }
            Path part = out.resolveSibling(out.getFileName() + "-" + (i++) + ".wav");
            Files.write(part, wav);
            parts.add(part);
        }
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y"));
        for (Path p : parts) args.addAll(List.of("-i", p.toString()));
        StringBuilder g = new StringBuilder();
        for (int k = 0; k < parts.size(); k++) {
            double gap = k == parts.size() - 1 ? 0 : 0.25;
            g.append('[').append(k).append(":a]aresample=48000,aformat=channel_layouts=stereo,apad=pad_dur=")
                    .append(fmt(gap)).append("[s").append(k).append("];");
        }
        for (int k = 0; k < parts.size(); k++) g.append("[s").append(k).append(']');
        g.append("concat=n=").append(parts.size()).append(":v=0:a=1[out]");
        args.addAll(List.of("-filter_complex", g.toString(), "-map", "[out]", "-ar", "48000", "-ac", "2", out.toString()));
        run(args);
        return out;
    }

    /** First speaker of each role keeps a stable voice for the whole scene. */
    private Map<String, String> assignIndicVoices(List<Shot> plan, IndicSpeech.Language lang) {
        List<String> pool = IndicSpeech.indicVoices(lang);
        Map<String, String> out = new LinkedHashMap<>();
        int characterIndex = 0;
        for (Shot shot : plan) {
            for (Piece p : shot.pieces()) {
                if (out.containsKey(p.speaker())) continue;
                String voice = p.voice() != null && p.voice().startsWith("indic:") ? p.voice() : null;
                boolean narrator = shot.kind() == ShotKind.VOICE_OVER;
                if (voice == null && narrator && indicNarratorVoice != null && !indicNarratorVoice.isBlank()) voice = indicNarratorVoice.trim();
                if (voice == null) {
                    if (pool.isEmpty()) {
                        throw new IllegalStateException("No IndicF5 voice for " + lang.name()
                                + ". Supported: Hindi, Kannada, Tamil, Telugu, Malayalam, Marathi, Bengali, Gujarati, English - or untick 'Indic TTS voice' to let H3 speak.");
                    }
                    // narrator = first (female) voice; characters alternate male / female
                    voice = narrator ? pool.get(0) : pool.get((1 + characterIndex++) % pool.size());
                }
                out.put(p.speaker(), voice);
            }
        }
        return out;
    }

    private Path narrationAudio(H3ScenePrompts.SceneContext ctx, List<H3ScenePrompts.Spoken> lines, double seconds,
                                long seed, Path base) throws IOException {
        var result = providerGateway.generateH3Audio(new VideoGenerationProvider.H3AudioRequest(
                H3ScenePrompts.voiceOverAudio(ctx, lines, seconds), seconds, seed, 0, audioTurbo));
        Path p = Path.of(base + "." + (result.fileExtension() == null ? "wav" : result.fileExtension()));
        Files.write(p, result.audioBytes());
        return p;
    }

    private VideoGenerationProvider.VideoGenerationResult video(String prompt, double seconds, Path startImage, boolean hasImage,
                                                                 Path referenceAudio, Path characterReference) {
        String workflow = hasImage ? "minimax-h3-image-to-video" : "minimax-h3-text-to-video";
        return providerGateway.generateVideo(new VideoGenerationProvider.VideoGenerationRequest(
                hasImage ? startImage.toString() : null, prompt, NEGATIVE, seconds, 0, 0, workflow,
                referenceAudio == null ? null : referenceAudio.toString(),
                characterReference != null && Files.isRegularFile(characterReference) ? characterReference.toString() : null,
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 0));
    }

    private static Path writeVideo(Path out, VideoGenerationProvider.VideoGenerationResult r) throws IOException {
        Files.write(out, r.videoBytes());
        if (ClipMerger.probeDuration(out) <= 0.2) throw new IllegalStateException("H3 returned an unusable clip.");
        return out;
    }

    private static List<H3ScenePrompts.Spoken> spoken(Shot shot, boolean voiceOver) {
        List<H3ScenePrompts.Spoken> out = new ArrayList<>();
        for (Piece p : shot.pieces()) out.add(new H3ScenePrompts.Spoken(p.speaker(), p.text(), p.delivery(), voiceOver, p.languageTag()));
        return out;
    }

    // ================================================================== audio cues

    private H3ScenePrompts.AudioCues audioCues(String audioSpecJson, String freeText) {
        List<String> ambience = new ArrayList<>();
        List<String> sfx = new ArrayList<>();
        String mood = null;
        Double intensity = null;
        StringBuilder rest = new StringBuilder();
        if (freeText != null && !freeText.isBlank()) {
            // The UI writes "Ambience: ...", "Sound effects: ...", "Music: ..., intensity 0.7".
            for (String row : freeText.split("\\r?\\n")) {
                String r = row.trim();
                String low = r.toLowerCase(Locale.ROOT);
                if (low.startsWith("ambience:")) ambience.add(trimEnd(r.substring(9)));
                else if (low.startsWith("sound effects:")) sfx.add(trimEnd(r.substring(14)));
                else if (low.startsWith("sfx:")) sfx.add(trimEnd(r.substring(4)));
                else if (low.startsWith("music:")) {
                    String m = trimEnd(r.substring(6));
                    Matcher im = Pattern.compile("intensity\\s*([0-9]*\\.?[0-9]+)").matcher(m);
                    if (im.find()) {
                        try { intensity = Double.parseDouble(im.group(1)); } catch (NumberFormatException ignored) { }
                        m = m.substring(0, im.start()).replaceAll("[,\\s]+$", "");
                    }
                    mood = m.replaceAll("(?i)\\bmusic\\b", "").trim();
                } else if (!r.isEmpty()) {
                    rest.append(r).append(' ');
                }
            }
        } else if (audioSpecJson != null && !audioSpecJson.isBlank()) {
            try {
                JsonNode root = mapper.readTree(audioSpecJson);
                for (JsonNode n : root.path("ambience")) if (!n.asText("").isBlank()) ambience.add(n.asText().trim());
                for (JsonNode n : root.path("sfx")) {
                    if (n.isTextual()) sfx.add(n.asText().trim());
                    else if (!n.path("event").asText("").isBlank()) {
                        String x = n.path("event").asText().trim();
                        if (!n.path("timing").asText("").isBlank()) x += " (" + n.path("timing").asText().trim() + ")";
                        sfx.add(x);
                    }
                }
                JsonNode music = root.path("music");
                if (music.isTextual()) mood = music.asText();
                else if (music.isObject()) {
                    mood = music.path("mood").asText(null);
                    if (music.path("intensity").isNumber()) intensity = music.path("intensity").asDouble();
                }
            } catch (Exception e) {
                log.debug("audioSpecJson not readable: {}", e.getMessage());
            }
        }
        return new H3ScenePrompts.AudioCues(ambience, sfx, mood, intensity, rest.toString().trim());
    }

    // ================================================================== ffmpeg

    private static void muxVoiceOver(Path video, Path audio, Path out, double seconds) {
        run(List.of("ffmpeg", "-nostdin", "-y", "-i", video.toString(), "-i", audio.toString(),
                "-filter_complex", "[0:v]tpad=stop_mode=clone:stop_duration=3,setpts=PTS-STARTPTS[v];"
                        + "[1:a]aresample=48000,aformat=channel_layouts=stereo,apad[a]",
                "-map", "[v]", "-map", "[a]", "-t", fmt(seconds),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", out.toString()));
    }

    /**
     * TTS voice on top; bed = separate file (dialogue) or the video's own H3 audio (voice-over),
     * side-chain ducked under the voice. Video frozen on its last frame if the voice runs longer.
     */
    private static void mixVoiceOverBed(Path video, Path voice, Path bed, boolean bedFromVideo, Path out, double seconds) {
        List<String> a = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-i", video.toString(), "-i", voice.toString()));
        boolean hasBed = bed != null || (bedFromVideo && hasAudio(video));
        if (bed != null) a.addAll(List.of("-i", bed.toString()));
        String bedLabel = bed != null ? "[2:a]" : "[0:a]";
        StringBuilder g = new StringBuilder("[0:v]tpad=stop_mode=clone:stop_duration=4,setpts=PTS-STARTPTS[v];")
                .append("[1:a]aresample=48000,aformat=channel_layouts=stereo,apad[voice];");
        if (hasBed) {
            g.append(bedLabel).append("aresample=48000,aformat=channel_layouts=stereo,volume=0.55,apad[bed];")
                    .append("[voice]asplit=2[vmix][vkey];")
                    .append("[bed][vkey]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=400[ducked];")
                    .append("[vmix][ducked]amix=inputs=2:duration=longest:normalize=0[a]");
        } else {
            g.append("[voice]anull[a]");
        }
        a.addAll(List.of("-filter_complex", g.toString(), "-map", "[v]", "-map", "[a]", "-t", fmt(seconds),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", out.toString()));
        run(a);
    }

    private static void sliceAudio(Path in, Path out, double start, double duration) {
        run(List.of("ffmpeg", "-nostdin", "-y", "-ss", fmt(Math.max(0, start)), "-i", in.toString(),
                "-t", fmt(Math.max(0.3, duration)), "-ar", "48000", "-ac", "1", out.toString()));
    }

    private static boolean hasAudio(Path file) {
        String out = capture(List.of("ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString()));
        return out.trim().startsWith("audio");
    }

    /** 48 kHz stereo, rumble cut, broadcast loudness - H3 decodes at 32 kHz and tends to run hot. */
    private void polishAudio(Path in, Path out) {
        String af = "highpass=f=60,loudnorm=I=" + fmt(loudnessTarget) + ":TP=-1.5:LRA=11,aresample=48000";
        run(List.of("ffmpeg", "-nostdin", "-y", "-i", in.toString(), "-c:v", "copy", "-af", af,
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart", out.toString()));
    }

    /** True when the last 0.35 s is still loud - the narrator was probably cut mid-word. */
    private static boolean speechRunsToEnd(Path audio) {
        try {
            String out = capture(List.of("ffmpeg", "-nostdin", "-sseof", "-0.35", "-i", audio.toString(),
                    "-af", "volumedetect", "-f", "null", "-"));
            Matcher m = Pattern.compile("mean_volume:\\s*(-?[0-9.]+) dB").matcher(out);
            return m.find() && Double.parseDouble(m.group(1)) > -28.0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int[] probeSize(Path video) {
        String out = capture(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=p=0", video.toString())).trim();
        String[] parts = out.split(",");
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (Exception e) {
            return new int[]{480, 832};
        }
    }

    private static void run(List<String> args) {
        String out = capture(args);
        if (out.startsWith("\u0000FAIL")) throw new IllegalStateException(out.substring(5));
    }

    /** Runs a process; returns its output. Failures come back prefixed with NUL+FAIL for run(). */
    private static String capture(List<String> args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return "\u0000FAIL" + args.get(0) + " timed out";
            }
            String out = new String(bytes, StandardCharsets.UTF_8);
            if (p.exitValue() != 0 && !args.contains("volumedetect")) {
                String tail = out.length() > 800 ? out.substring(out.length() - 800) : out;
                return "\u0000FAIL" + args.get(0) + " failed: " + tail;
            }
            return out;
        } catch (IOException e) {
            return "\u0000FAIL" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "\u0000FAILinterrupted";
        }
    }

    // ================================================================== small helpers

    private static boolean looksLikeOutOfMemory(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("out of memory") || m.contains("outofmemory") || m.contains("cuda oom") || m.contains("allocation on device")) return true;
        }
        return false;
    }

    /** Same narrator seed for every scene of a story -> more consistent narrator voice. */
    private static long stableSeed(String key) {
        if (key == null || key.isBlank()) return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        long h = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) h = 31 * h + key.charAt(i);
        return Math.abs(h % (Long.MAX_VALUE - 1)) + 1;
    }

    private static double sum(List<Piece> pieces) {
        return pieces.stream().mapToDouble(Piece::seconds).sum();
    }

    private static int even(int v) {
        return Math.max(2, v - (v % 2));
    }

    private static String trimEnd(String s) {
        return s.trim().replaceAll("[.\\s]+$", "");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
