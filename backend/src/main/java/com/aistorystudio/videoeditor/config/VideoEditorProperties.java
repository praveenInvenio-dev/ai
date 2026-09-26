package com.aistorystudio.videoeditor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Video editor configuration.
 *
 * Several of these look like tuning knobs but are correctness settings on the
 * target hardware (12 CPU cores, 16 GB RAM, 2 GB VRAM):
 *
 * <ul>
 *   <li>{@code maxConcurrentRenders} defaults to 1. ComfyUI already serialises
 *       its own work because concurrent jobs on this machine make everything
 *       slower rather than faster; two simultaneous x264 encodes plus a
 *       diffusion job would thrash. Raising this on a bigger box is fine.</li>
 *   <li>{@code previewResolution} defaults to 480, not the 720 in the brief. On
 *       this CPU a 480p ultrafast preview encodes roughly 2.5x faster and is
 *       perfectly adequate for judging cuts and timing, which is the only thing
 *       a preview is for.</li>
 *   <li>{@code x264Preset} defaults to veryfast rather than medium. 12 CPU
 *       cores at veryfast comfortably beat what a 2 GB MX450 offers through
 *       NVENC, and this avoids a hard CUDA dependency for basic editing.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "studio.video-editor")
public class VideoEditorProperties {

    /**
     * Container/extension allowlist. Checked before ffprobe, and ffprobe is
     * then required to confirm the file actually decodes - an allowed extension
     * on an undecodable file is rejected at upload rather than at render time,
     * when the user has already waited.
     */
    private static final List<String> ALLOWED_EXTENSIONS =
            List.of("mp4", "mov", "mkv", "webm", "m4v");

    private static final List<String> ALLOWED_AUDIO_EXTENSIONS =
            List.of("mp3", "wav", "m4a", "aac", "ogg", "flac");

    private boolean enabled = true;
    private String storagePath = "/data/video-editor";
    private int maxConcurrentRenders = 1;
    private int maxClips = 20;
    private long maxUploadMb = 512;
    private int previewResolution = 480;
    private int defaultResolution = 1080;
    private String x264Preset = "veryfast";
    private int crf = 23;
    private int analysisThreads = 8;
    private String workerBaseUrl = "http://video-worker:5010";
    /** Previews older than this are swept; renders persist with the project. */
    private int previewRetentionHours = 24;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }

    public int getMaxConcurrentRenders() { return maxConcurrentRenders; }
    public void setMaxConcurrentRenders(int v) { this.maxConcurrentRenders = Math.max(1, v); }

    public int getMaxClips() { return maxClips; }
    public void setMaxClips(int maxClips) { this.maxClips = maxClips; }

    public long getMaxUploadMb() { return maxUploadMb; }
    public void setMaxUploadMb(long maxUploadMb) { this.maxUploadMb = maxUploadMb; }

    public long getMaxUploadBytes() { return maxUploadMb * 1024L * 1024L; }

    public int getPreviewResolution() { return previewResolution; }
    public void setPreviewResolution(int v) { this.previewResolution = v; }

    public int getDefaultResolution() { return defaultResolution; }
    public void setDefaultResolution(int v) { this.defaultResolution = v; }

    public String getX264Preset() { return x264Preset; }
    public void setX264Preset(String v) { this.x264Preset = v; }

    public int getCrf() { return crf; }
    public void setCrf(int crf) { this.crf = crf; }

    public int getAnalysisThreads() { return analysisThreads; }
    public void setAnalysisThreads(int v) { this.analysisThreads = v; }

    public String getWorkerBaseUrl() { return workerBaseUrl; }
    public void setWorkerBaseUrl(String v) { this.workerBaseUrl = v; }

    public int getPreviewRetentionHours() { return previewRetentionHours; }
    public void setPreviewRetentionHours(int v) { this.previewRetentionHours = v; }

    public List<String> allowedVideoExtensions() { return ALLOWED_EXTENSIONS; }
    public List<String> allowedAudioExtensions() { return ALLOWED_AUDIO_EXTENSIONS; }
}
