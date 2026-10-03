package com.aistorystudio.provider;

import java.nio.file.Path;
import java.util.List;

public interface MediaProcessor {

    /** Assemble a set of scene images + narration + optional music into a final video. */
    Path assembleVideo(VideoAssemblyRequest request);

    /** Burn-in or produce sidecar subtitles from an SRT file. */
    Path muxSubtitles(Path videoPath, Path srtPath, boolean burnIn);

    /**
     * Adds (or replaces) the audio track on an otherwise-silent video - the
     * generative video models this project uses (Wan) have no audio channel
     * at all, so a narration/voiceover track is always a separate mux step
     * after the fact, not something the video model itself produces. Video
     * stream is copied unchanged (fast, no re-encode, no quality loss);
     * audio is re-encoded to AAC to match the mp4 container. Trims to
     * whichever of video/audio is SHORTER (ffmpeg's -shortest) rather than
     * looping or padding - a mismatch here should be visible/obvious to the
     * caller (e.g. "narration ran long"), not silently papered over.
     */
    Path addAudioTrack(Path videoPath, byte[] audioBytes, Path outputPath);

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
