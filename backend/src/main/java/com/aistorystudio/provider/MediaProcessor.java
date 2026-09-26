package com.aistorystudio.provider;

import java.nio.file.Path;
import java.util.List;

public interface MediaProcessor {

    /** Assemble a set of scene images + narration + optional music into a final video. */
    Path assembleVideo(VideoAssemblyRequest request);

    /** Burn-in or produce sidecar subtitles from an SRT file. */
    Path muxSubtitles(Path videoPath, Path srtPath, boolean burnIn);

    /** Produce a vertical short (9:16) from a sub-range of scenes. */
    Path renderShort(Path sourceVideo, double startSeconds, double endSeconds, Path outputPath);

    double probeDurationSeconds(Path mediaFile);

    /**
     * Runs raw concatenated TTS output through a real post-processing chain
     * (high-pass, noise reduction, compression, EBU R128 loudness
     * normalization, limiter) so narration sounds like a produced voice
     * track rather than unprocessed synthesizer output straight into the
     * final video. Returns the input unchanged if processing fails - a
     * missing polish step should never block the whole scene.
     */
    byte[] humanizeVoice(byte[] wavBytes);

    /**
     * Resolves a built-in music preset id ("calm", "adventurous",
     * "emotional") to a real file, extracting the bundled classpath
     * resource once and caching it. Null if the id is unknown - callers
     * should treat that the same as "no music" rather than failing the
     * whole render over a bad preset id.
     */
    java.nio.file.Path musicPresetPath(String presetId);

    /** Spec section 12: the explicit JSON motion-profile artifact for one
     *  scene, reflecting the same decisions the renderer actually makes. */
    String buildMotionProfileJson(SceneClip scene);

    record SceneClip(Path imagePath, Path audioPath, double durationSeconds, String cameraMovement,
                      String transitionIn, String emotion, String lighting, String action, String location,
                      String importance, String animationMode, Path aiVideoPath) {
        public SceneClip(Path imagePath, Path audioPath, double durationSeconds, String cameraMovement,
                         String transitionIn) {
            this(imagePath, audioPath, durationSeconds, cameraMovement, transitionIn, null, null, null, null, null, "AUTO", null);
        }
    }

    record VideoAssemblyRequest(
            List<SceneClip> scenes,
            Path musicPath,
            Path outputPath,
            int width,
            int height
    ) {}
}
