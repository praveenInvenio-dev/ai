package com.aistorystudio.videoeditor.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import com.aistorystudio.videoeditor.dub.DubCore;
import com.aistorystudio.videoeditor.editing.TimelineRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * "Dub this video" (Localization &amp; dub): listens to the latest rendered cut, translates what is said into another language with
 * lines sized to their time slots, speaks them with a voice of that language and stores the result as the project's voiceover with
 * "replace the original voice" on. The next Preview / Render mixes it in; the work is matched to the cut as it is now, so dub again
 * after editing the timeline.
 */
@Service
public class DubService {

    public static final List<String> LANGUAGES = List.of("Hindi", "Kannada", "Tamil", "Telugu", "Malayalam", "Marathi", "Bengali", "Gujarati", "English");

    public record Result(String language, int lines, int placed, int sped, int silent, double seconds, List<String> preview, List<String> notes) { }

    private final ProviderGateway gateway;
    private final VideoEditorProjectService projects;
    private final VideoEditorStorageService storage;
    private final TimelineRenderer renderer;
    private final String workerBaseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public DubService(ProviderGateway gateway, VideoEditorProjectService projects, VideoEditorStorageService storage, TimelineRenderer renderer,
                      @Value("${studio.video-editor.worker-base-url:http://video-worker:5010}") String workerBaseUrl) {
        this.gateway = gateway;
        this.projects = projects;
        this.storage = storage;
        this.renderer = renderer;
        this.workerBaseUrl = workerBaseUrl;
    }

    public Result dub(UUID projectId, String language, String voice, String model) throws Exception {
        projects.get(projectId);
        String lang = LANGUAGES.stream().filter(l -> l.equalsIgnoreCase(language == null ? "" : language.trim())).findFirst().orElse(null);
        if (lang == null) throw new IllegalArgumentException("Choose one of: " + String.join(", ", LANGUAGES) + ".");
        Path video = latestRender(projectId);
        double total = renderer.probeDuration(video);
        if (total <= 1) throw new IllegalArgumentException("The latest render has no usable length - render a Preview again.");

        List<String> notes = new ArrayList<>();
        JsonNode transcript = transcribe(video);
        List<DubCore.Cue> cues = DubCore.cues(transcript, total);
        if (cues.isEmpty()) throw new IllegalArgumentException("No speech was found in the latest render, so there is nothing to dub.");
        cues = DubCore.translate(gateway.llm(), model == null || model.isBlank() ? null : model.trim(), cues, lang, notes);

        String v = voice == null || voice.isBlank() ? "narrator-in-male" : voice.trim();
        DubCore.Voice speaker = text -> {
            TextToSpeechProvider.TtsResult r = gateway.synthesizeForStoryLanguage(new TextToSpeechProvider.TtsRequest(text, v, lang, 1.0, 1.0));
            if (r.providerWarning() != null && !notes.contains(r.providerWarning())) notes.add(r.providerWarning());
            return new DubCore.Audio(r.audioBytes(), r.format());
        };
        Path work = Files.createTempDirectory("dub-");
        try {
            Path wav = work.resolve("dub.wav");
            DubCore.Report rep = DubCore.buildTrack(renderer.ffmpegBin(), cues, speaker, total, wav, work);
            notes.addAll(rep.notes());
            if (rep.placed() == 0) throw new IllegalStateException("None of the lines could be spoken: " + String.join(" ", notes));
            projects.addAudioBytes(projectId, Files.readAllBytes(wav), "wav", "Dub (" + lang + ").wav", AudioTrackKind.VOICEOVER);
            projects.setVoiceoverReplace(projectId, true);
            List<String> preview = new ArrayList<>();
            for (DubCore.Cue c : cues) if (preview.size() < 6 && c.translated() != null && !c.translated().isBlank())
                preview.add(String.format(Locale.ROOT, "%d:%02d  %s", (int) c.start() / 60, (int) c.start() % 60, c.translated()));
            return new Result(lang, cues.size(), rep.placed(), rep.sped(), rep.skipped(), total, preview, notes);
        } finally {
            try (Stream<Path> w = Files.walk(work)) { w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }); }
            catch (IOException ignored) { }
        }
    }

    /** The newest finished render of the project (a Preview or a Final), ignoring the finishing passes' temporary files. */
    private Path latestRender(UUID projectId) throws IOException {
        try (Stream<Path> s = Files.list(storage.rendersDir(projectId))) {
            return s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return (n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mov") || n.endsWith(".mkv")) && !n.startsWith("fin-") && !n.startsWith("ln-");
            }).max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalArgumentException("Render a Preview first - the dub is matched to the finished cut."));
        }
    }

    private JsonNode transcribe(Path video) throws Exception {
        var body = mapper.createObjectNode().put("source", video.toAbsolutePath().toString());
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(workerBaseUrl + "/api/transcribe"))
                .timeout(Duration.ofMinutes(30)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        JsonNode n = mapper.readTree(resp.body());
        if (resp.statusCode() >= 300) throw new IllegalStateException("Transcription failed: " + n.path("error").asText(resp.body()));
        return n;
    }
}
