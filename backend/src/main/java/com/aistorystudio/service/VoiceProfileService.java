package com.aistorystudio.service;

import com.aistorystudio.config.ProviderGateway;
import com.aistorystudio.domain.VoiceProfile;
import com.aistorystudio.provider.TextToSpeechProvider;
import com.aistorystudio.repository.VoiceProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records/uploads, validates, converts, and stores reference audio for a
 * reusable VoiceProfile - Phase 1 of the Voice Library. Reference audio
 * lives in its OWN shared volume (voice-profile-audio, mounted into both the
 * backend and tts-chatterbox containers), separate from StorageProvider's
 * project-scoped storage, because that audio needs to be readable by the
 * tts-chatterbox container directly - a different container, a different
 * volume, on purpose, same reasoning as the existing tts-data/comfyui-data
 * split by concern.
 *
 * Validation uses ffmpeg/ffprobe (already in this image - see
 * FFmpegProcessor for the established pattern this mirrors) rather than a
 * new audio-DSP dependency: duration, silence, and clipping checks are all
 * things ffmpeg's own filters already do well.
 */
@Service
public class VoiceProfileService {

    private static final Logger log = LoggerFactory.getLogger(VoiceProfileService.class);
    private static final Pattern MEAN_VOLUME = Pattern.compile("mean_volume:\\s*(-?[\\d.]+)\\s*dB");
    private static final Pattern MAX_VOLUME = Pattern.compile("max_volume:\\s*(-?[\\d.]+)\\s*dB");
    private static final Pattern SILENCE_DURATION = Pattern.compile("silence_duration:\\s*([\\d.]+)");

    private final VoiceProfileRepository repository;
    private final ProviderGateway providerGateway;
    private final Path audioDir;

    public VoiceProfileService(
            VoiceProfileRepository repository,
            ProviderGateway providerGateway,
            @Value("${studio.voiceProfile.audioDir:/voice-profiles}") String audioDir) {
        this.repository = repository;
        this.providerGateway = providerGateway;
        this.audioDir = Path.of(audioDir);
    }

    public record ValidationResult(boolean ok, String error, List<String> warnings,
                                    double durationSeconds, int sampleRate) {}

    public List<VoiceProfile> list() {
        return repository.findAllByOrderByNameAsc();
    }

    public VoiceProfile get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Voice profile not found: " + id));
    }

    /** Built-in voice - no audio to store, voiceName is an existing Piper
     *  voice id (e.g. from TTS_PRELOAD_VOICES). Kept as its own method rather
     *  than an overload with a lot of nulls, so the two real cases (built-in
     *  vs cloned) read clearly at the call site. */
    public VoiceProfile createBuiltIn(String name, String language, String piperVoiceName, String personality) {
        VoiceProfile profile = new VoiceProfile();
        profile.setName(name);
        profile.setLanguage(language);
        profile.setProvider("piper");
        profile.setVoiceName(piperVoiceName);
        profile.setPersonality(personality);
        return repository.save(profile);
    }

    /**
     * Validates, converts, and stores a recorded/uploaded reference clip,
     * then creates the VoiceProfile. Throws (mapped to 422 by
     * GlobalExceptionHandler) on a hard validation failure; soft issues
     * (background noise, borderline duration) are returned as warnings on
     * the ValidationResult a caller can inspect via validateOnly() before
     * committing, rather than silently accepted here.
     */
    public VoiceProfile createCloned(String name, String language, String provider,
                                      String personality, String referenceTranscript, MultipartFile audio) {
        String normalizedProvider = provider == null ? "" : provider.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("chatterbox", "cosyvoice", "cosyvoice3", "minimax-h3").contains(normalizedProvider)) {
            throw new IllegalStateException(
                    "Unsupported voice provider for a cloned voice: " + provider
                    + " (supported: chatterbox, cosyvoice)");
        }
        if (("cosyvoice".equals(normalizedProvider) || "cosyvoice3".equals(normalizedProvider))
                && (referenceTranscript == null || referenceTranscript.isBlank())) {
            throw new IllegalStateException("CosyVoice requires the exact transcript of the reference recording.");
        }
        // Convert FIRST, validate the converted wav - not the raw upload. See
        // validateConvertedFile()'s comment for the real bug this order
        // fixes (raw WebM's unreliable duration metadata reporting ~2s
        // regardless of actual recording length). One conversion, not two -
        // this same file gets moved into permanent storage below if valid,
        // deleted if not.
        UUID id = UUID.randomUUID();
        Path converted = convertToTargetFormat(audio, id);
        ValidationResult validation = validateConvertedFile(converted);
        if (!validation.ok()) {
            try { Files.deleteIfExists(converted); } catch (IOException ignored) { }
            throw new IllegalStateException(validation.error());
        }

        try {
            Files.createDirectories(audioDir);
            Files.move(converted, audioDir.resolve(id + ".wav"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not store voice reference audio", e);
        }

        VoiceProfile profile = new VoiceProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setLanguage(language);
        profile.setProvider(provider.toLowerCase(java.util.Locale.ROOT));
        profile.setReferenceAudioKey(id.toString());
        profile.setVoiceName(id.toString());
        profile.setReferenceTranscript(referenceTranscript);
        profile.setPersonality(personality);
        profile.setDurationSeconds(validation.durationSeconds());
        profile.setSampleRate(validation.sampleRate());
        return repository.save(profile);
    }

    /** Same checks createCloned() enforces, exposed separately so the
     *  frontend can show warnings (background noise, clipping, borderline
     *  duration) at the Preview step, before the user commits to Save. */
    public ValidationResult validateOnly(MultipartFile audio) {
        return validate(audio);
    }

    public VoiceProfile rename(UUID id, String newName) {
        VoiceProfile profile = get(id);
        profile.setName(newName);
        profile.setUpdatedAt(Instant.now());
        return repository.save(profile);
    }

    /** Returns the exact stored reference clip so the Voice Library can
     *  distinguish a recording/storage problem from a TTS-generation problem. */
    public byte[] getReferenceAudio(UUID id) {
        VoiceProfile profile = get(id);
        if (profile.getReferenceAudioKey() == null || profile.getReferenceAudioKey().isBlank()) {
            throw new IllegalStateException("This voice does not have a stored reference recording.");
        }
        Path file = audioDir.resolve(profile.getReferenceAudioKey() + ".wav").normalize();
        if (!file.startsWith(audioDir.normalize()) || !Files.isRegularFile(file)) {
            throw new IllegalStateException("The stored reference recording is missing for voice profile " + id);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read the stored reference recording", e);
        }
    }

    /** Absolute path used by native H3 Reference-to-Video voice conditioning. */
    public Path getReferenceAudioPath(UUID id) {
        VoiceProfile profile = get(id);
        if (profile.getReferenceAudioKey() == null || profile.getReferenceAudioKey().isBlank()) {
            throw new IllegalStateException("This voice does not have a stored reference recording.");
        }
        Path file = audioDir.resolve(profile.getReferenceAudioKey() + ".wav").normalize();
        if (!file.startsWith(audioDir.normalize()) || !Files.isRegularFile(file)) {
            throw new IllegalStateException("The stored reference recording is missing for voice profile " + id);
        }
        return file;
    }

    public void delete(UUID id) {
        VoiceProfile profile = get(id);
        if (profile.getReferenceAudioKey() != null) {
            try {
                Files.deleteIfExists(audioDir.resolve(profile.getReferenceAudioKey() + ".wav"));
            } catch (IOException e) {
                log.warn("Could not delete reference audio for voice profile {}: {}", id, e.getMessage());
            }
        }
        repository.delete(profile);
    }

    /** Generates a short sample using this profile's actual provider/voice -
     *  same code path a real episode would use (ProviderGateway.synthesize),
     *  not a separate preview mechanism that could drift from real output. */
    public TextToSpeechProvider.TtsResult generateTest(UUID id, String sampleText) {
        return generateTest(id, sampleText, 1.0, 1.0);
    }

    public TextToSpeechProvider.TtsResult generateTest(UUID id, String sampleText, double speed, double pitch) {
        VoiceProfile profile = get(id);
        String text = (sampleText == null || sampleText.isBlank())
                ? "Hello! This is what I sound like."
                : sampleText;
        // TtsRequest.voice() is provider-specific: a Piper voice id for
        // provider=piper, or this profile's stored filename key for a
        // cloned provider - both already sit in voiceName, so this call
        // doesn't need to branch on provider itself.
        return providerGateway.synthesizeWithVoiceStrict(profile.getProvider(), profile.getVoiceName(), text,
                profile.getLanguage(), profile.getReferenceTranscript());
    }

    /** Was: probed duration/silence/clipping directly on the RAW uploaded
     *  file. Real bug that caused this: a browser MediaRecorder WebM blob
     *  has NO reliable duration in its container header (it's built for
     *  streaming, not playback) - ffprobe reading that raw file returns a
     *  bogus, often short duration (confirmed live: every recording showing
     *  as ~2s regardless of actual length recorded) no matter how long the
     *  real recording was. A plain WAV file (what convertToTargetFormat
     *  already produces) has none of this ambiguity - duration is exact,
     *  byte-count based. Fix: always validate the CONVERTED wav, never the
     *  raw upload. This method takes an already-converted wav path;
     *  validate(MultipartFile) below does the upload+convert+cleanup dance
     *  around it for the preview-before-save case, and createCloned() calls
     *  this directly on the same file it's about to store, so nothing gets
     *  converted twice. */
    private ValidationResult validateConvertedFile(Path wavFile) {
        double duration = probeDuration(wavFile);
        int sampleRate = probeSampleRate(wavFile);
        if (duration <= 0) {
            return new ValidationResult(false, "That file doesn't look like valid audio.", List.of(), 0, 0);
        }
        if (duration < 5.0) {
            return new ValidationResult(false,
                    "That clip is too short (" + String.format("%.1f", duration) + "s) - "
                    + "record at least 5 seconds. For best cloning quality, use a clean 5-15 second sample and record again.", List.of(), duration, sampleRate);
        }
        if (duration > 60.0) {
            return new ValidationResult(false,
                    "That clip is too long (" + String.format("%.1f", duration) + "s) - "
                    + "a clean 5-15 second sample clones better than a long one.", List.of(), duration, sampleRate);
        }

        List<String> warnings = new java.util.ArrayList<>();
        if (duration < 5.0 || duration > 10.0) {
            warnings.add("5-10 seconds is the recommended length; this clip is "
                    + String.format("%.1f", duration) + "s.");
        }

        VolumeStats volume = probeVolume(wavFile);
        if (volume != null) {
            if (volume.maxVolume >= -1.0) {
                warnings.add("This clip may be clipping (peaks very close to 0dB) - "
                        + "re-record slightly quieter if the test sample sounds distorted.");
            }
            if (volume.meanVolume <= -35.0) {
                warnings.add("This clip is very quiet overall - check the recording is actually "
                        + "capturing your voice, not mostly silence/background noise.");
            }
        }

        double silence = probeSilenceDuration(wavFile);
        if (duration > 0 && silence / duration > 0.5) {
            warnings.add("More than half of this clip appears to be silence - "
                    + "trim dead air for a cleaner reference.");
        }

        return new ValidationResult(true, null, warnings, duration, sampleRate);
    }

    /** Preview-before-save path only - converts to a throwaway temp wav,
     *  validates it, deletes it. createCloned() does NOT call this; it
     *  validates the real converted file directly to avoid converting twice. */
    private ValidationResult validate(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            return new ValidationResult(false, "No audio was uploaded.", List.of(), 0, 0);
        }
        Path wav;
        try {
            wav = convertToTargetFormat(audio, UUID.randomUUID());
        } catch (Exception e) {
            return new ValidationResult(false, "Could not read the uploaded audio: " + e.getMessage(), List.of(), 0, 0);
        }
        try {
            return validateConvertedFile(wav);
        } finally {
            try { Files.deleteIfExists(wav); } catch (IOException ignored) { }
        }
    }

    /** Mono, 24kHz WAV - the target format noted in .env's Chatterbox
     *  section, chosen to match what chatterbox-tts expects internally
     *  without relying on its own resampling. */
    private Path convertToTargetFormat(MultipartFile audio, UUID id) {
        Path in;
        Path out;
        try {
            in = Files.createTempFile("voice-in-", ".audio");
            audio.transferTo(in);
            out = Files.createTempFile("voice-out-" + id, ".wav");
            Files.deleteIfExists(out); // ffmpeg refuses to overwrite by default
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not prepare voice audio for conversion", e);
        }
        run(List.of("ffmpeg", "-y", "-i", in.toString(), "-ar", "24000", "-ac", "1", out.toString()));
        try {
            Files.deleteIfExists(in);
        } catch (IOException ignored) { }
        return out;
    }

    private double probeDuration(Path file) {
        try {
            List<String> args = List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toString());
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(30, TimeUnit.SECONDS);
            return Double.parseDouble(out);
        } catch (Exception e) {
            log.warn("ffprobe duration probe failed: {}", e.getMessage());
            return -1;
        }
    }

    private int probeSampleRate(Path file) {
        try {
            List<String> args = List.of("ffprobe", "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=sample_rate", "-of", "default=noprint_wrappers=1:nokey=1", file.toString());
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(30, TimeUnit.SECONDS);
            return Integer.parseInt(out);
        } catch (Exception e) {
            return 0;
        }
    }

    private record VolumeStats(double meanVolume, double maxVolume) {}

    private VolumeStats probeVolume(Path file) {
        try {
            List<String> args = List.of("ffmpeg", "-i", file.toString(), "-af", "volumedetect", "-f", "null", "-");
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            Matcher mean = MEAN_VOLUME.matcher(out);
            Matcher max = MAX_VOLUME.matcher(out);
            if (mean.find() && max.find()) {
                return new VolumeStats(Double.parseDouble(mean.group(1)), Double.parseDouble(max.group(1)));
            }
        } catch (Exception e) {
            log.warn("Volume probe failed: {}", e.getMessage());
        }
        return null;
    }

    private double probeSilenceDuration(Path file) {
        try {
            List<String> args = List.of("ffmpeg", "-i", file.toString(), "-af",
                    "silencedetect=noise=-35dB:d=0.3", "-f", "null", "-");
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            Matcher m = SILENCE_DURATION.matcher(out);
            double total = 0;
            while (m.find()) {
                total += Double.parseDouble(m.group(1));
            }
            return total;
        } catch (Exception e) {
            log.warn("Silence probe failed: {}", e.getMessage());
            return 0;
        }
    }

    private void run(List<String> args) {
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg timed out converting voice audio");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg failed converting voice audio (exit=" + process.exitValue() + "): " + output);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to invoke ffmpeg for voice audio", e);
        }
    }
}
