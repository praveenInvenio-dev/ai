package com.aistorystudio.videoeditor.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.videoeditor.domain.AudioTrack;
import com.aistorystudio.videoeditor.domain.enums.AudioTrackKind;
import com.aistorystudio.videoeditor.editing.TimelineRenderer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * "AI voiceover from a script" (Cardboard parity). Speaks the script sentence by sentence with the chosen voice (any engine the
 * app has: Indian-English / Hindi / Kannada ... voices, Voice Lab clones), joins the lines with short natural pauses and stores the
 * result as the project's voiceover track. The render then mixes it in and ducks the rest of the sound underneath.
 */
@Service
public class VoiceoverService {

    private static final int MAX_SENTENCES = 60;
    private static final int MAX_CHARS = 6000;

    public record Result(AudioTrack track, int sentences, double seconds, String note) { }

    private final ProviderGateway gateway;
    private final VideoEditorProjectService projects;
    private final TimelineRenderer renderer;

    public VoiceoverService(ProviderGateway gateway, VideoEditorProjectService projects, TimelineRenderer renderer) {
        this.gateway = gateway;
        this.projects = projects;
        this.renderer = renderer;
    }

    public Result generate(UUID projectId, String script, String voice, String language, Double speed) throws IOException, InterruptedException {
        projects.get(projectId);
        String text = script == null ? "" : script.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Write or paste the voiceover script first.");
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("That script is longer than " + MAX_CHARS + " characters - split it in two.");
        List<String> sentences = split(text);
        if (sentences.isEmpty()) throw new IllegalArgumentException("Could not find any sentences in that script.");
        if (sentences.size() > MAX_SENTENCES) throw new IllegalArgumentException("That is more than " + MAX_SENTENCES + " sentences - split the script.");

        String v = voice == null || voice.isBlank() ? "narrator-in-male" : voice.trim();
        String lang = language == null || language.isBlank() ? "English" : language.trim();
        double sp = speed == null ? 1.0 : Math.max(0.7, Math.min(1.3, speed));

        Path work = Files.createTempDirectory("voiceover-");
        try {
            String bin = renderer.ffmpegBin();
            List<Path> parts = new ArrayList<>();
            Path gap = work.resolve("gap.wav");
            run(List.of(bin, "-y", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono", "-t", "0.28", gap.toString()));
            String note = null;
            int i = 0;
            for (String sentence : sentences) {
                TextToSpeechProvider.TtsResult r = gateway.synthesizeForStoryLanguage(new TextToSpeechProvider.TtsRequest(sentence, v, lang, sp, 1.0));
                if (note == null && r.providerWarning() != null) note = r.providerWarning();
                Path raw = work.resolve("raw-" + i + "." + (r.format() == null ? "wav" : r.format().replace(".", "")));
                Files.write(raw, r.audioBytes());
                Path wav = work.resolve("line-" + i + ".wav");
                run(List.of(bin, "-y", "-i", raw.toString(), "-ar", "24000", "-ac", "1", wav.toString()));
                if (!parts.isEmpty()) parts.add(gap);
                parts.add(wav);
                i++;
            }
            StringBuilder list = new StringBuilder();
            for (Path p : parts) list.append("file '").append(p.toAbsolutePath().toString().replace("'", "")).append("'\n");
            Path listFile = work.resolve("list.txt");
            Files.writeString(listFile, list.toString());
            Path joined = work.resolve("voiceover.wav");
            run(List.of(bin, "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(), "-c", "copy", joined.toString()));
            double seconds = renderer.probeDuration(joined);
            AudioTrack track = projects.addAudioBytes(projectId, Files.readAllBytes(joined), "wav", "AI voiceover.wav", AudioTrackKind.VOICEOVER);
            return new Result(track, sentences.size(), seconds, note);
        } finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.replace('\r', '\n').split("(?<=[.!?\u0964\u3002])\\s+|\\n+")) {
            String t = s.trim();
            if (t.length() > 1) out.add(t);
        }
        return out;
    }

    private static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] log = p.getInputStream().readAllBytes();
        if (!p.waitFor(5, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("Audio tool timed out."); }
        if (p.exitValue() != 0) throw new IOException("Audio tool failed: " + new String(log).replaceAll("\\s+", " ").trim());
    }
}
