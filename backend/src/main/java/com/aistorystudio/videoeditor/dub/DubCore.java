package com.aistorystudio.videoeditor.dub;

import com.aistorystudio.provider.StoryLLMProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The working parts of "dub this video into another language" (Localization &amp; dub): transcript -&gt; cues, translation written to fit
 * each cue's time slot, speech sped up when it would not fit, and everything placed on ONE audio track at the right times.
 * No Spring and no database: the model, the voice and FFmpeg are passed in, so every step can be tested on its own.
 */
public final class DubCore {

    private DubCore() { }

    public static final int RATE = 48000;
    public static final int MAX_CUES = 160;
    private static final ObjectMapper M = new ObjectMapper();

    /** @param translated null until translated; blank = nothing to say */
    public record Cue(int index, double start, double end, String text, String translated) {
        public Cue withTranslation(String t) { return new Cue(index, start, end, text, t); }
        public double seconds() { return end - start; }
    }

    /** One synthesised sentence: the bytes and their file extension (wav, mp3 ...). */
    public record Audio(byte[] bytes, String ext) { }

    public interface Voice { Audio speak(String text) throws Exception; }

    /** What happened to a cue while building the track (for the notes shown to the user). */
    public record Report(int placed, int sped, int trimmed, int skipped, List<String> notes) { }

    // ------------------------------------------------------------------------------------------------------ cues

    /** Speech segments of a transcript ({segments:[{text,start,end}]}), tidied; very short neighbours are merged, at most MAX_CUES. */
    public static List<Cue> cues(JsonNode transcript, double totalSeconds) {
        List<double[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (JsonNode s : transcript.path("segments")) {
            String t = s.path("text").asText("").replaceAll("\\s+", " ").trim();
            double a = Math.max(0, s.path("start").asDouble(0)), b = Math.min(totalSeconds > 0 ? totalSeconds : 1e9, s.path("end").asDouble(a));
            if (t.isEmpty() || b - a < 0.15) continue;
            if (!times.isEmpty() && (b - a < 0.9 || a - times.get(times.size() - 1)[1] < 0.12) && b - times.get(times.size() - 1)[0] < 7.5) {
                times.get(times.size() - 1)[1] = b;                                   // glue a fragment onto the previous line
                texts.set(texts.size() - 1, texts.get(texts.size() - 1) + " " + t);
            } else {
                times.add(new double[]{a, b});
                texts.add(t);
            }
        }
        while (times.size() > MAX_CUES) {                                             // too many: merge the closest pair until it fits
            int best = 0;
            double gap = 1e9;
            for (int i = 0; i + 1 < times.size(); i++) {
                double g = times.get(i + 1)[0] - times.get(i)[1];
                if (g < gap) { gap = g; best = i; }
            }
            times.get(best)[1] = times.get(best + 1)[1];
            texts.set(best, texts.get(best) + " " + texts.get(best + 1));
            times.remove(best + 1);
            texts.remove(best + 1);
        }
        List<Cue> out = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) out.add(new Cue(i, times.get(i)[0], times.get(i)[1], texts.get(i), null));
        return out;
    }

    // ------------------------------------------------------------------------------------------------------ translation

    /** Words per second a speaker of the language manages comfortably (used to size each translated line to its slot). */
    static double wordsPerSecond(String language) {
        String l = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if (l.startsWith("kannada") || l.startsWith("tamil") || l.startsWith("telugu") || l.startsWith("malayalam")) return 2.0;
        if (l.startsWith("marathi")) return 2.3;
        if (l.startsWith("bengali")) return 2.4;
        if (l.startsWith("gujarati")) return 2.5;
        if (l.startsWith("hindi")) return 2.8;
        return 2.6;
    }

    static String systemPrompt(String language) {
        return "You translate spoken video dialogue into " + language + " for DUBBING. Rules:\n"
                + "- translate the meaning naturally, the way a native speaker would say it; not word for word;\n"
                + "- keep names, brand names and technical terms people normally say in English as they are;\n"
                + "- each line is spoken inside the same time window as the original, so keep it to MAXWORDS words or fewer;\n"
                + "- write numbers as digits; no notes, no quotes, no stage directions;\n"
                + "Return JSON only: {\"lines\":[{\"i\":N,\"text\":\"...\"}]} with one entry per input line.";
    }

    /**
     * Translates all cues, a batch at a time. A line the model skipped or garbled keeps translated = "" (it is not spoken) and is
     * counted in {@code notes}; a failed batch does not lose the others.
     */
    public static List<Cue> translate(StoryLLMProvider llm, String model, List<Cue> cues, String language, List<String> notes) {
        List<Cue> out = new ArrayList<>(cues);
        double wps = wordsPerSecond(language);
        for (int from = 0; from < cues.size(); from += 12) {
            int to = Math.min(cues.size(), from + 12);
            ArrayNode items = M.createArrayNode();
            for (int i = from; i < to; i++) {
                Cue c = cues.get(i);
                ObjectNode o = items.addObject();
                o.put("i", c.index());
                o.put("seconds", Math.round(c.seconds() * 10) / 10.0);
                o.put("maxWords", Math.max(2, (int) Math.ceil(c.seconds() * wps)));
                o.put("text", c.text());
            }
            Map<Integer, String> got = new java.util.HashMap<>();
            try {
                String raw = llm.generateStructured(systemPrompt(language), "Lines to translate:\n" + items, model);
                JsonNode root = M.readTree(extractJson(raw));
                for (JsonNode l : root.path("lines")) {
                    String t = l.path("text").asText("").replaceAll("\\s+", " ").trim();
                    if (l.has("i") && !t.isEmpty() && t.length() < 600) got.put(l.path("i").asInt(), t);
                }
            } catch (Exception e) {
                notes.add("Lines " + (from + 1) + "-" + to + " could not be translated (" + shortMessage(e) + ").");
            }
            for (int i = from; i < to; i++) out.set(i, cues.get(i).withTranslation(got.getOrDefault(cues.get(i).index(), "")));
        }
        long missing = out.stream().filter(c -> c.translated() == null || c.translated().isBlank()).count();
        if (missing > 0) notes.add(missing + " of " + out.size() + " lines were not translated and stay silent.");
        return out;
    }

    // ------------------------------------------------------------------------------------------------------ the track

    /**
     * Speaks every translated cue and places it at the cue's start on one mono 48 kHz track of {@code totalSeconds}. A sentence longer than
     * the time before the next cue is sped up (at most 1.45x) and, if it is still too long, cut with a short fade. Writes {@code out} (WAV).
     */
    public static Report buildTrack(String ffmpeg, List<Cue> cues, Voice voice, double totalSeconds, Path out, Path work) throws IOException, InterruptedException {
        int total = (int) Math.ceil(totalSeconds * RATE);
        float[] mix = new float[total];
        List<String> notes = new ArrayList<>();
        int placed = 0, sped = 0, trimmed = 0, skipped = 0;
        for (int k = 0; k < cues.size(); k++) {
            Cue c = cues.get(k);
            if (c.translated() == null || c.translated().isBlank()) { skipped++; continue; }
            double slotEnd = k + 1 < cues.size() ? Math.min(totalSeconds, cues.get(k + 1).start() - 0.05) : totalSeconds;
            double slot = Math.max(0.4, slotEnd - c.start());
            byte[] raw;
            try {
                Audio a = voice.speak(c.translated());
                Path in = Files.createTempFile(work, "dub-", "." + (a.ext() == null ? "wav" : a.ext()));
                Files.write(in, a.bytes());
                raw = toPcm(ffmpeg, in, 1.0);
                double d = raw.length / 2.0 / RATE;
                if (d > slot * 1.0) {
                    double tempo = Math.min(1.45, d / slot);
                    raw = toPcm(ffmpeg, in, tempo);
                    sped++;
                    d = raw.length / 2.0 / RATE;
                    if (d > slot + 0.05) trimmed++;
                }
                Files.deleteIfExists(in);
                int maxSamples = (int) Math.min(raw.length / 2, Math.round((slot + 0.05) * RATE));
                int offset = (int) Math.round(c.start() * RATE);
                ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                int fadeIn = (int) (RATE * 0.008), fadeOut = (int) (RATE * 0.03);
                for (int i = 0; i < maxSamples && offset + i < total; i++) {
                    float v = bb.getShort(i * 2) / 32768f;
                    if (i < fadeIn) v *= i / (float) fadeIn;
                    if (maxSamples - i < fadeOut) v *= (maxSamples - i) / (float) fadeOut;
                    mix[offset + i] += v;
                }
                placed++;
            } catch (Exception e) {
                skipped++;
                notes.add("Line " + (c.index() + 1) + " could not be spoken (" + shortMessage(e) + ").");
            }
        }
        writeWav(out, mix);
        return new Report(placed, sped, trimmed, skipped, notes);
    }

    private static byte[] toPcm(String ffmpeg, Path in, double tempo) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-nostdin", "-i", in.toString(), "-vn"));
        if (Math.abs(tempo - 1.0) > 0.001) cmd.addAll(List.of("-af", String.format(Locale.ROOT, "atempo=%.3f", tempo)));
        cmd.addAll(List.of("-ar", String.valueOf(RATE), "-ac", "1", "-f", "s16le", "-"));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) { is.transferTo(bos); }
        if (!p.waitFor(2, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("audio conversion timed out"); }
        if (p.exitValue() != 0 || bos.size() < 100) throw new IOException("the voice returned audio that could not be read");
        return bos.toByteArray();
    }

    private static void writeWav(Path out, float[] mix) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + mix.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + mix.length * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(mix.length * 2);
        for (float f : mix) b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(f * 32767f))));       // hard limit: overlapping speech never wraps
        Files.write(out, b.array());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** The JSON object in a model answer, which may be wrapped in a code fence or chatter. */
    static String extractJson(String raw) {
        if (raw == null) return "{}";
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        return a >= 0 && b > a ? raw.substring(a, b + 1) : "{}";
    }

    static String shortMessage(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }
}
