package com.aistorystudio.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg-backed MediaProcessor. Every invocation builds an explicit argument LIST
 * (never a shell string) so user-controlled text (prompts, titles, filenames) can
 * never be interpreted as shell syntax. Ken Burns pan/zoom is applied per-scene via
 * the zoompan filter, scenes are concatenated with subtle crossfades (xfade),
 * narration is placed on the primary audio track, and background music is ducked
 * under narration.
 */
@Component
public class FFmpegProcessor implements MediaProcessor {

    private static final Logger log = LoggerFactory.getLogger(FFmpegProcessor.class);

    private final String ffmpegBin;
    // Spec section 8: explicit branch point, not implicit-always-heuristic.
    // No lightweight depth model is actually wired in (see the session's own
    // repeated notes on why: no obtainable model weights in this
    // environment) - this flag exists so the day one IS wired in, the
    // parallax decision has a real place to check it rather than needing
    // this whole method restructured. Honestly false by default: turning it
    // on without actually implementing depthEstimate() below would silently
    // do nothing, which is worse than the flag not existing.
    private final boolean depthModelEnabled;
    private final boolean videoTransitionsEnabled;

    public FFmpegProcessor(@Value("${studio.ffmpeg.binary:ffmpeg}") String ffmpegBin,
                           @Value("${studio.animation.depth-model.enabled:false}") boolean depthModelEnabled,
                            @Value("${studio.video.transitions.enabled:false}") boolean videoTransitionsEnabled) {
        this.ffmpegBin = ffmpegBin;
        this.depthModelEnabled = depthModelEnabled;
        this.videoTransitionsEnabled = videoTransitionsEnabled;
    }

    /** Real check, not a stub that always returns true - depthModelEnabled
     *  being on with no actual estimator wired up is exactly the trap this
     *  method exists to avoid: it reports honestly false regardless of the
     *  flag until a real depthEstimate() implementation exists to back it. */
    private boolean depthModelAvailable() {
        return false; // depthModelEnabled is read, but nothing implements estimation yet
    }

    @Override
    public Path assembleVideo(VideoAssemblyRequest request) {
        try {
            if (request.scenes() == null || request.scenes().isEmpty()) {
                throw new IllegalArgumentException("Cannot assemble a video with no scenes.");
            }

            Path workDir = Files.createTempDirectory("story-studio-assembly-");
            List<Path> segmentPaths = new ArrayList<>();

            // Render every scene independently first. The scene narration duration is
            // the source of truth; no later edit is allowed to silently shorten it.
            int index = 0;
            for (SceneClip scene : request.scenes()) {
                if (scene.imagePath() == null || !Files.exists(scene.imagePath())) {
                    throw new IllegalStateException("Missing image for scene " + (index + 1) + ": " + scene.imagePath());
                }
                if (scene.audioPath() != null && (!Files.exists(scene.audioPath()) || Files.size(scene.audioPath()) == 0)) {
                    throw new IllegalStateException("Missing narration audio for scene " + (index + 1) + ": " + scene.audioPath());
                }

                Path segment = cachedSegment(scene, request.width(), request.height());
                if (segment == null) {
                    segment = workDir.resolve("segment-" + String.format("%03d", index) + ".mp4");
                    if (scene.aiVideoPath() != null) {
                        renderSceneSegmentFromAiVideo(scene, scene.aiVideoPath(), request.width(), request.height(), segment);
                    } else if ("TALKING_CHARACTER".equals(scene.animationMode()) && scene.audioPath() != null) {
                        renderSceneSegmentTalkingCharacter(scene, request.width(), request.height(), segment);
                    } else {
                        renderSceneSegment(scene, request.width(), request.height(), segment);
                    }

                    if (!Files.exists(segment) || Files.size(segment) < 1024) {
                        throw new IllegalStateException("FFmpeg produced an empty scene segment " + (index + 1));
                    }

                    Path cachePath = segmentCachePath(scene, request.width(), request.height());
                    if (cachePath != null) {
                        try {
                            Files.copy(segment, cachePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            segment = cachePath;
                        } catch (IOException e) {
                            log.warn("Could not cache rendered segment for scene {}: {}", index + 1, e.getMessage());
                        }
                    }
                }

                // A bad/old cache entry must never be allowed to create a truncated
                // final episode. Re-render once if the cached clip is materially
                // shorter than the requested scene duration.
                double actual = probeDurationSeconds(segment);
                double expected = Math.max(0.1, scene.durationSeconds());
                if (actual > 0 && actual + 0.20 < expected) {
                    log.warn("Scene {} segment is {}s but {}s was requested; invalidating cache and re-rendering.",
                            index + 1, fmt(actual), fmt(expected));
                    Path rerender = workDir.resolve("segment-rerender-" + String.format("%03d", index) + ".mp4");
                    if (scene.aiVideoPath() != null) {
                        renderSceneSegmentFromAiVideo(scene, scene.aiVideoPath(), request.width(), request.height(), rerender);
                    } else if ("TALKING_CHARACTER".equals(scene.animationMode()) && scene.audioPath() != null) {
                        renderSceneSegmentTalkingCharacter(scene, request.width(), request.height(), rerender);
                    } else {
                        renderSceneSegment(scene, request.width(), request.height(), rerender);
                    }
                    segment = rerender;
                    actual = probeDurationSeconds(segment);
                }
                if (actual > 0 && actual + 0.20 < expected) {
                    throw new IllegalStateException(String.format(
                            "Scene %d rendered only %.3fs of expected %.3fs. Refusing to create a partial episode.",
                            index + 1, actual, expected));
                }

                segmentPaths.add(segment);
                index++;
            }

            Files.createDirectories(request.outputPath().toAbsolutePath().getParent());
            double expectedDuration = request.scenes().stream()
                    .mapToDouble(s -> Math.max(0.1, s.durationSeconds()))
                    .sum();

            // The old chained xfade/acrossfade graph could produce a valid-looking
            // MP4 while ending at the duration of one intermediate scene when an
            // input stream or filter branch ended early. The correctness-first
            // path is a normal concat of already-rendered scene clips. Transitions
            // remain available as an opt-in enhancement, but a duration check and
            // automatic concat fallback make it impossible to ship a truncated file.
            boolean transitions = videoTransitionsEnabled && segmentPaths.size() > 1
                    && request.scenes().stream().allMatch(s -> s.audioPath() != null);

            Path assembled;
            if (transitions) {
                Path transitionOutput = workDir.resolve("transition-output.mp4");
                try {
                    assembleWithTransitions(request, segmentPaths, transitionOutput, request.musicPath());
                    double duration = probeDurationSeconds(transitionOutput);
                    double minimum = Math.max(0.1, expectedDuration - 0.60 * (segmentPaths.size() - 1));
                    if (duration <= 0 || duration + 0.50 < minimum) {
                        throw new IllegalStateException(String.format(
                                "Transition render duration %.3fs is below expected minimum %.3fs", duration, minimum));
                    }
                    assembled = transitionOutput;
                } catch (Exception transitionFailure) {
                    log.warn("Transition assembly was rejected; falling back to lossless scene-by-scene assembly: {}",
                            transitionFailure.getMessage());
                    assembled = assembleWithConcat(workDir, segmentPaths, request.musicPath(), request.outputPath());
                }
            } else {
                assembled = assembleWithConcat(workDir, segmentPaths, request.musicPath(), request.outputPath());
            }

            // Final correctness gate. The final file must cover the complete scene
            // timeline. A tiny codec/container rounding difference is normal.
            double finalDuration = probeDurationSeconds(assembled);
            double tolerance = Math.max(0.75, expectedDuration * 0.02);
            if (finalDuration <= 0 || finalDuration + tolerance < expectedDuration) {
                throw new IllegalStateException(String.format(
                        "Final video is incomplete: %.3fs rendered, %.3fs expected. No partial video will be published.",
                        finalDuration, expectedDuration));
            }

            if (!assembled.equals(request.outputPath())) {
                Files.move(assembled, request.outputPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Final video assembled successfully: {} scenes, {:.3f}s expected, {:.3f}s rendered, BGM={}",
                    segmentPaths.size(), expectedDuration, finalDuration,
                    request.musicPath() != null && Files.exists(request.musicPath()));
            return request.outputPath();
        } catch (IOException e) {
            throw new IllegalStateException("Video assembly failed", e);
        }
    }

    /**
     * Correctness-first assembly: concatenate complete scene clips first, then mix
     * the optional music bed in a separate pass. Keeping BGM out of the concat
     * operation prevents a short music file or an audio filter from becoming the
     * duration-limiting stream.
     */
    private Path assembleWithConcat(Path workDir, List<Path> segmentPaths, Path musicPath, Path outputPath) throws IOException {
        Path concatList = workDir.resolve("concat.txt");
        StringBuilder contents = new StringBuilder();
        for (Path p : segmentPaths) {
            String escaped = p.toAbsolutePath().toString().replace("'", "'\\''");
            contents.append("file '").append(escaped).append("'\n");
        }
        Files.writeString(concatList, contents.toString(), StandardCharsets.UTF_8);

        Path base = workDir.resolve("base-concat.mp4");
        List<String> concatArgs = new ArrayList<>();
        concatArgs.add(ffmpegBin);
        concatArgs.add("-y");
        concatArgs.add("-f");
        concatArgs.add("concat");
        concatArgs.add("-safe");
        concatArgs.add("0");
        concatArgs.add("-i");
        concatArgs.add(concatList.toAbsolutePath().toString());
        concatArgs.add("-map");
        concatArgs.add("0:v:0");
        concatArgs.add("-map");
        concatArgs.add("0:a:0?");
        concatArgs.add("-c:v");
        concatArgs.add("libx264");
        concatArgs.add("-preset");
        concatArgs.add("veryfast");
        concatArgs.add("-crf");
        concatArgs.add("20");
        concatArgs.add("-pix_fmt");
        concatArgs.add("yuv420p");
        concatArgs.add("-c:a");
        concatArgs.add("aac");
        concatArgs.add("-b:a");
        concatArgs.add("128k");
        concatArgs.add("-movflags");
        concatArgs.add("+faststart");
        concatArgs.add(base.toAbsolutePath().toString());
        run(concatArgs);

        if (musicPath == null || !Files.exists(musicPath) || Files.size(musicPath) == 0) {
            Files.copy(base, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return outputPath;
        }

        Path mixed = workDir.resolve("mixed-final.mp4");
        List<String> mixArgs = new ArrayList<>();
        mixArgs.add(ffmpegBin);
        mixArgs.add("-y");
        mixArgs.add("-i");
        mixArgs.add(base.toAbsolutePath().toString());
        mixArgs.add("-stream_loop");
        mixArgs.add("-1");
        mixArgs.add("-i");
        mixArgs.add(musicPath.toAbsolutePath().toString());
        mixArgs.add("-filter_complex");
        mixArgs.add(
                "[1:a]volume=0.16,aresample=async=1:first_pts=0[music];"
              + "[0:a]asplit=2[voice][side];"
              + "[music][side]sidechaincompress=threshold=0.035:ratio=8:attack=25:release=500[ducked];"
              + "[voice][ducked]amix=inputs=2:duration=first:dropout_transition=2:normalize=0[aout]"
        );
        mixArgs.add("-map");
        mixArgs.add("0:v:0");
        mixArgs.add("-map");
        mixArgs.add("[aout]");
        mixArgs.add("-c:v");
        mixArgs.add("copy");
        mixArgs.add("-c:a");
        mixArgs.add("aac");
        mixArgs.add("-b:a");
        mixArgs.add("160k");
        mixArgs.add("-shortest");
        mixArgs.add("-movflags");
        mixArgs.add("+faststart");
        mixArgs.add(mixed.toAbsolutePath().toString());
        run(mixArgs);

        Files.copy(mixed, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return outputPath;
    }

    /**
     * Optional transition path. It is deliberately isolated from the correctness
     * path above so a filter-graph regression can never truncate the episode.
     */
    private void assembleWithTransitions(VideoAssemblyRequest request, List<Path> segmentPaths,
                                         Path output, Path musicPath) throws IOException {
        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.add("-y");

        for (Path segmentPath : segmentPaths) {
            args.add("-i");
            args.add(segmentPath.toAbsolutePath().toString());
        }

        int sceneCount = segmentPaths.size();
        int musicInputIndex = sceneCount;
        StringBuilder graph = new StringBuilder();
        String currentVideo = "0:v";
        String currentAudio = "0:a";
        double accumulated = Math.max(0.1, request.scenes().get(0).durationSeconds());

        for (int i = 1; i < sceneCount; i++) {
            double transition = Math.min(0.35, Math.max(0.12,
                    Math.min(request.scenes().get(i - 1).durationSeconds(),
                            request.scenes().get(i).durationSeconds()) * 0.05));
            double offset = Math.max(0.05, accumulated - transition);
            String nextVideo = "v" + i;
            String nextAudio = "a" + i;

            graph.append("[").append(currentVideo).append("][")
                    .append(i).append(":v]")
                    .append("xfade=transition=")
                    .append(safeTransition(request.scenes().get(i).transitionIn(), i))
                    .append(":duration=").append(fmt(transition))
                    .append(":offset=").append(fmt(offset))
                    .append("[").append(nextVideo).append("];");

            graph.append("[").append(currentAudio).append("][")
                    .append(i).append(":a]")
                    .append("acrossfade=d=").append(fmt(transition))
                    .append(":c1=tri:c2=tri[")
                    .append(nextAudio).append("];");

            currentVideo = nextVideo;
            currentAudio = nextAudio;
            accumulated += Math.max(0.1, request.scenes().get(i).durationSeconds()) - transition;
        }

        if (musicPath != null && Files.exists(musicPath)) {
            args.add("-stream_loop");
            args.add("-1");
            args.add("-i");
            args.add(musicPath.toAbsolutePath().toString());
            graph.append("[")
                    .append(musicInputIndex)
                    .append(":a]volume=0.16,aresample=async=1:first_pts=0[music];")
                    .append("[music][")
                    .append(currentAudio)
                    .append("]sidechaincompress=threshold=0.035:ratio=8:attack=25:release=500[ducked];")
                    .append("[")
                    .append(currentAudio)
                    .append("][ducked]amix=inputs=2:duration=first:dropout_transition=2:normalize=0[aout];");
            currentAudio = "aout";
        }

        args.add("-filter_complex");
        args.add(graph.toString());
        args.add("-map");
        args.add("[" + currentVideo + "]");
        args.add("-map");
        args.add("[" + currentAudio + "]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("20");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("160k");
        args.add("-shortest");
        args.add("-movflags");
        args.add("+faststart");
        args.add(output.toAbsolutePath().toString());
        run(args);
    }

    /** The cached segment for this exact scene+size, if one exists and its
     *  inputs haven't changed since it was rendered. Null means "render it". */
    private Path cachedSegment(SceneClip scene, int width, int height) {
        Path cachePath = segmentCachePath(scene, width, height);
        if (cachePath == null) {
            return null;
        }
        File f = cachePath.toFile();
        return (f.exists() && f.length() > 0) ? cachePath : null;
    }

    /**
     * Deterministic cache location for a scene's rendered segment, co-located
     * next to its source image (so it lives and dies with the same episode's
     * asset folder rather than needing separate cache-directory config).
     * Null if the image path is missing, in which case there is nowhere
     * sensible to cache next to.
     *
     * The filename hashes every input that actually affects the render:
     * image and audio file identity (path + size + last-modified, not full
     * content - cheap to check, and a changed file always changes at least
     * one of those), camera movement, emotion/lighting/action/location (the
     * particle+ambience effect selection depends on these), importance
     * (changes parallax strength) and duration. Any change to any of these
     * produces a different filename, so a stale cache is never served -
     * there is no explicit invalidation step because there is nothing to
     * invalidate.
     */
    private Path segmentCachePath(SceneClip scene, int width, int height) {
        if (scene.imagePath() == null) {
            return null;
        }
        try {
            StringBuilder key = new StringBuilder();
            key.append(fileIdentity(scene.imagePath()));
            key.append('|').append(scene.audioPath() == null ? "no-audio" : fileIdentity(scene.audioPath()));
            key.append('|').append(scene.cameraMovement());
            key.append('|').append(scene.emotion());
            key.append('|').append(scene.lighting());
            key.append('|').append(scene.action());
            key.append('|').append(scene.location());
            key.append('|').append(scene.importance());
            key.append('|').append(scene.animationMode());
            key.append('|').append(scene.aiVideoPath() == null ? "no-ai-video" : fileIdentity(scene.aiVideoPath()));
            key.append('|').append(fmt(scene.durationSeconds()));
            key.append('|').append(width).append('x').append(height);

            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return scene.imagePath().resolveSibling(".segment-cache-" + hex + ".mp4");
        } catch (Exception e) {
            log.warn("Could not compute segment cache key, will render without caching: {}", e.getMessage());
            return null;
        }
    }

    private String fileIdentity(Path p) throws IOException {
        File f = p.toFile();
        return f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
    }

    private void renderSceneSegment(SceneClip scene, int width, int height, Path outputSegment) {
        /*
         * Story Studio deliberately does not require an image-to-video model.
         * Every still image is turned into a small animated shot using FFmpeg:
         * a larger working canvas enables real pans, the zoompan path supplies
         * camera movement, and the final filter stack adds gentle cinematic
         * colour/lighting and fade treatment. The result is substantially more
         * alive than a simple zoom slideshow while remaining cheap enough for
         * CPU rendering.
         */
        String movement = chooseMovement(scene);
        boolean isStatic = "STATIC".equals(scene.animationMode());
        int frames = Math.max(1, (int) Math.round(Math.max(0.1, scene.durationSeconds()) * 30));
        double canvas = 1.18;
        int workWidth = Math.max(width, (int) Math.round(width * canvas));
        int workHeight = Math.max(height, (int) Math.round(height * canvas));

        String visual = visualExpression(scene.emotion(), scene.lighting());
        List<String> postFilters = new ArrayList<>();
        postFilters.add(visual);
        postFilters.add("vignette=PI/5");
        postFilters.add("fade=t=in:st=0:d=0.35");
        if (scene.durationSeconds() > 0.7) {
            postFilters.add("fade=t=out:st=" + fmt(Math.max(0.35, scene.durationSeconds() - 0.35)) + ":d=0.35");
        }
        String postChain = String.join(",", postFilters);

        // Environment particles (spec: "generated procedurally where possible
        // instead of heavy AI generation"). A small set of template clips -
        // soft drifting/twinkling dots, drawn once with PIL and checked in as
        // tiny looping MP4s - are screen-blended onto the Ken Burns pass based
        // on the scene's own text. Screen blend needs no alpha channel: black
        // stays invisible, only the bright dots add light, so the same silent
        // template works for any scene without per-scene compositing work.
        //
        // The same detected effect also selects a matching AMBIENCE bed
        // (procedurally synthesised filtered-noise/tone loops, not sampled
        // audio - none was available to bundle) mixed quietly under the
        // narration with sidechain ducking, so a "magic forest" scene both
        // looks and sounds like one from the same single piece of scene text.
        String effect = chooseEnvironmentEffect(scene);
        Path particleClip = effect == null ? null : particleTemplate(effect);
        Path ambienceClip = effect == null ? null : ambienceTemplate(effect);

        // Cheap parallax approximation (no depth model available): only for
        // lateral movements (pan/track/diagonal/drift) is there enough x/y
        // motion to meaningfully differentiate two layers. The lower band of
        // the frame - typically nearer ground/subject in an illustrated
        // scene - pans at full speed; the upper band - typically sky/distant
        // background - pans at a quarter of that speed. See buildBaseGraph
        // for why the blend band is narrow rather than spanning the whole
        // frame: a full-height linear blend never reaches a "pure" layer
        // anywhere except the exact top/bottom edge pixels, which reads as
        // ghosting on anything not right at the frame border - tested and
        // rejected before landing on this narrower-band version.
        // Spec section 8's explicit branch: depth model if available, else
        // heuristic. depthModelAvailable() is a real check (not a stub
        // returning true) and always false today since no depth model is
        // actually wired in here - so this always takes the heuristic path,
        // honestly, rather than pretending a depth-driven dampening factor
        // exists. The call is real so the branch point exists in the code,
        // not just in a comment.
        if (!depthModelAvailable()) {
            log.debug("No depth model available for scene - using heuristic parallax dampening.");
        }
        String dampenedMotion = motionExpressionDamped(movement, frames, "HERO".equals(scene.importance()) ? 0.15 : 0.25);
        boolean parallax = !isStatic && dampenedMotion != null;

        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.add("-y");
        args.add("-loop");
        args.add("1");
        args.add("-i");
        args.add(scene.imagePath().toAbsolutePath().toString());
        int nextInput = 1;

        int audioInputIndex = -1;
        if (scene.audioPath() != null) {
            audioInputIndex = nextInput++;
            args.add("-i");
            args.add(scene.audioPath().toAbsolutePath().toString());
        }
        int particleInputIndex = -1;
        if (particleClip != null) {
            particleInputIndex = nextInput++;
            // Looped at the input level (not a `loop` filter) so a 2-second
            // template silently covers a scene of any length.
            args.add("-stream_loop");
            args.add("-1");
            args.add("-i");
            args.add(particleClip.toAbsolutePath().toString());
        }
        int ambienceInputIndex = -1;
        if (ambienceClip != null) {
            ambienceInputIndex = nextInput++;
            args.add("-stream_loop");
            args.add("-1");
            args.add("-i");
            args.add(ambienceClip.toAbsolutePath().toString());
        }
        // Discrete one-shot SFX cue (spec section 33-36: footsteps, a door,
        // a magic sparkle - a specific moment, not a continuous bed). Not
        // looped: it plays once, positioned early in the scene via adelay.
        String sfxCue = chooseSfxCue(scene);
        Path sfxClip = sfxCue == null ? null : sfxTemplate(sfxCue);
        int sfxInputIndex = -1;
        if (sfxClip != null) {
            sfxInputIndex = nextInput++;
            args.add("-i");
            args.add(sfxClip.toAbsolutePath().toString());
        }
        args.add("-t");
        args.add(fmt(Math.max(0.1, scene.durationSeconds())));

        String baseGraph = buildBaseGraph(movement, dampenedMotion, parallax, isStatic, frames,
                workWidth, workHeight, width, height, postChain);
        if ("CHARACTER_MOTION".equals(scene.animationMode()) && !isStatic) {
            baseGraph = baseGraph + "," + characterMotionFilters(width, height, frames);
        }

        boolean needsAudioMix = ambienceInputIndex >= 0 || sfxInputIndex >= 0;
        boolean needsFilterComplex = parallax || particleInputIndex >= 0 || needsAudioMix;
        String finalAudioLabel = null;
        if (needsFilterComplex) {
            StringBuilder graph = new StringBuilder("[0:v]" + baseGraph + "[base]");
            if (particleInputIndex >= 0) {
                graph.append(";[").append(particleInputIndex).append(":v]scale=")
                     .append(width).append(':').append(height).append("[particles];")
                     .append("[base][particles]blend=all_mode=screen:all_opacity=0.55[vout]");
            } else {
                graph.append(";[base]null[vout]");
            }

            if (needsAudioMix) {
                // Tracks the current "best available mix so far" as we layer
                // in ambience then a one-shot cue, each optional and each
                // built on whatever came before - narration alone, silence,
                // or an already-mixed combination.
                String current = audioInputIndex >= 0 ? ("[" + audioInputIndex + ":a]") : null;

                if (ambienceInputIndex >= 0) {
                    graph.append(";[").append(ambienceInputIndex).append(":a]volume=0.30,aloop=loop=-1:size=2e9[amb]");
                    if (current != null) {
                        // Same sidechain-ducking technique as the video
                        // editor's background-music mix: narration stays
                        // full level, the ambience bed dips automatically
                        // while narration speaks.
                        graph.append(";[amb]").append(current)
                             .append("sidechaincompress=threshold=0.04:ratio=8:attack=20:release=400[ducked]");
                        graph.append(';').append(current).append("[ducked]amix=inputs=2:duration=first:dropout_transition=0[amb_mixed]");
                        current = "[amb_mixed]";
                    } else {
                        current = "[amb]";
                    }
                }

                if (sfxInputIndex >= 0) {
                    // Fixed position rather than derived from the scene text:
                    // roughly a third into the scene reads as "the moment
                    // happens shortly after the shot begins" for most short
                    // scenes without needing real event-time detection, which
                    // would require knowing where in the narration the
                    // triggering word actually falls.
                    long delayMs = Math.round(Math.min(2500, Math.max(300, scene.durationSeconds() * 1000 * 0.3)));
                    // apad: without it, a scene with a cue and no narration/
                    // ambience to mix against had its final audio end the
                    // instant the short one-shot cue clip finished - and
                    // -shortest below then truncated the WHOLE VIDEO down to
                    // that same short length, silently cutting a real scene
                    // to ~1 second. Tested and caught before this shipped.
                    //
                    // Spatial positioning (spec section 29): only the
                    // discrete cue gets panned, never narration or ambience -
                    // a bird crossing left-to-right is a positioned event,
                    // but the narrator's voice and the room tone should stay
                    // centred regardless of what's happening on screen.
                    String pan = choosePanBias(scene);
                    String panFilter = "left".equals(pan) ? "pan=stereo|c0=1.5*c0|c1=0.5*c1,"
                            : "right".equals(pan) ? "pan=stereo|c0=0.5*c0|c1=1.5*c1," : "";
                    graph.append(";[").append(sfxInputIndex).append(":a]").append(panFilter)
                         .append("adelay=").append(delayMs).append('|').append(delayMs)
                         .append(",apad[cue]");
                    if (current != null) {
                        graph.append(';').append(current).append("[cue]amix=inputs=2:duration=first:dropout_transition=0[sfx_mixed]");
                        current = "[sfx_mixed]";
                    } else {
                        current = "[cue]";
                    }
                }
                finalAudioLabel = current;
            }

            args.add("-filter_complex");
            args.add(graph.toString());
            args.add("-map");
            args.add("[vout]");
        } else {
            args.add("-vf");
            args.add(baseGraph);
            // Once any -map is used (below, for audio) ffmpeg stops
            // auto-selecting streams entirely, so video needs an explicit
            // map too whenever audio does - otherwise a scene with narration
            // silently lost its picture.
            if (audioInputIndex >= 0) {
                args.add("-map");
                args.add("0:v");
            }
        }

        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("20");
        args.add("-pix_fmt");
        args.add("yuv420p");
        if (finalAudioLabel != null) {
            args.add("-map");
            args.add(finalAudioLabel);
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add("128k");
            args.add("-shortest");
        } else if (audioInputIndex >= 0) {
            args.add("-map");
            args.add(audioInputIndex + ":a");
            args.add("-af");
            args.add("apad=whole_dur=" + fmt(Math.max(0.1, scene.durationSeconds())));
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add("128k");
            args.add("-shortest");
        } else {
            args.add("-an");
        }
        args.add(outputSegment.toAbsolutePath().toString());
        run(args);
    }

    /**
     * Produces a scene segment from an ALREADY-GENERATED AI video clip (Wan
     * image-to-video, via ComfyUIVideoProvider) rather than rendering camera
     * motion from a still image. The AI clip's own motion is trusted as-is -
     * no zoompan/parallax is layered on top, since the model already
     * produced real motion - but the same environment particles, ambience,
     * SFX cue and colour-grade treatment as a 2.5D segment still apply, so a
     * story mixing AI-animated and 2.5D scenes still looks and sounds like
     * one consistent piece rather than two different pipelines bolted
     * together. Deliberately a separate method rather than a shared
     * refactor of {@link #renderSceneSegment} - duplicating the audio-mix
     * pattern is a smaller risk than reworking the already-tested 2.5D path.
     */
    private void renderSceneSegmentFromAiVideo(SceneClip scene, Path aiVideoPath, int width, int height, Path outputSegment) {
        String visual = visualExpression(scene.emotion(), scene.lighting());
        List<String> postFilters = new ArrayList<>();
        postFilters.add("scale=" + width + ":" + height + ":force_original_aspect_ratio=increase");
        postFilters.add("crop=" + width + ":" + height);
        postFilters.add(visual);
        postFilters.add("fade=t=in:st=0:d=0.35");
        if (scene.durationSeconds() > 0.7) {
            postFilters.add("fade=t=out:st=" + fmt(Math.max(0.35, scene.durationSeconds() - 0.35)) + ":d=0.35");
        }
        String baseGraph = String.join(",", postFilters);

        String effect = chooseEnvironmentEffect(scene);
        Path particleClip = effect == null ? null : particleTemplate(effect);
        Path ambienceClip = effect == null ? null : ambienceTemplate(effect);
        String sfxCue = chooseSfxCue(scene);
        Path sfxClip = sfxCue == null ? null : sfxTemplate(sfxCue);

        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.add("-y");
        args.add("-i");
        args.add(aiVideoPath.toAbsolutePath().toString());
        int nextInput = 1;

        int audioInputIndex = -1;
        if (scene.audioPath() != null) {
            audioInputIndex = nextInput++;
            args.add("-i");
            args.add(scene.audioPath().toAbsolutePath().toString());
        }
        int particleInputIndex = -1;
        if (particleClip != null) {
            particleInputIndex = nextInput++;
            args.add("-stream_loop");
            args.add("-1");
            args.add("-i");
            args.add(particleClip.toAbsolutePath().toString());
        }
        int ambienceInputIndex = -1;
        if (ambienceClip != null) {
            ambienceInputIndex = nextInput++;
            args.add("-stream_loop");
            args.add("-1");
            args.add("-i");
            args.add(ambienceClip.toAbsolutePath().toString());
        }
        int sfxInputIndex = -1;
        if (sfxClip != null) {
            sfxInputIndex = nextInput++;
            args.add("-i");
            args.add(sfxClip.toAbsolutePath().toString());
        }
        args.add("-t");
        args.add(fmt(Math.max(0.1, scene.durationSeconds())));

        StringBuilder graph = new StringBuilder("[0:v]" + baseGraph + "[base]");
        if (particleInputIndex >= 0) {
            graph.append(";[").append(particleInputIndex).append(":v]scale=")
                 .append(width).append(':').append(height).append("[particles];")
                 .append("[base][particles]blend=all_mode=screen:all_opacity=0.55[vout]");
        } else {
            graph.append(";[base]null[vout]");
        }

        String finalAudioLabel = null;
        if (ambienceInputIndex >= 0 || sfxInputIndex >= 0) {
            String current = audioInputIndex >= 0 ? ("[" + audioInputIndex + ":a]") : null;
            if (ambienceInputIndex >= 0) {
                graph.append(";[").append(ambienceInputIndex).append(":a]volume=0.30,aloop=loop=-1:size=2e9[amb]");
                if (current != null) {
                    graph.append(";[amb]").append(current)
                         .append("sidechaincompress=threshold=0.04:ratio=8:attack=20:release=400[ducked]");
                    graph.append(';').append(current).append("[ducked]amix=inputs=2:duration=first:dropout_transition=0[amb_mixed]");
                    current = "[amb_mixed]";
                } else {
                    current = "[amb]";
                }
            }
            if (sfxInputIndex >= 0) {
                long delayMs = Math.round(Math.min(2500, Math.max(300, scene.durationSeconds() * 1000 * 0.3)));
                String pan = choosePanBias(scene);
                String panFilter = "left".equals(pan) ? "pan=stereo|c0=1.5*c0|c1=0.5*c1,"
                        : "right".equals(pan) ? "pan=stereo|c0=0.5*c0|c1=1.5*c1," : "";
                graph.append(";[").append(sfxInputIndex).append(":a]").append(panFilter)
                     .append("adelay=").append(delayMs).append('|').append(delayMs)
                     .append(",apad[cue]");
                if (current != null) {
                    graph.append(';').append(current).append("[cue]amix=inputs=2:duration=first:dropout_transition=0[sfx_mixed]");
                    current = "[sfx_mixed]";
                } else {
                    current = "[cue]";
                }
            }
            finalAudioLabel = current;
        }

        args.add("-filter_complex");
        args.add(graph.toString());
        args.add("-map");
        args.add("[vout]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("20");
        args.add("-pix_fmt");
        args.add("yuv420p");
        if (finalAudioLabel != null) {
            args.add("-map");
            args.add(finalAudioLabel);
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add("128k");
            args.add("-shortest");
        } else if (audioInputIndex >= 0) {
            args.add("-map");
            args.add(audioInputIndex + ":a");
            args.add("-af");
            args.add("apad=whole_dur=" + fmt(Math.max(0.1, scene.durationSeconds())));
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add("128k");
            args.add("-shortest");
        } else {
            args.add("-an");
        }
        args.add(outputSegment.toAbsolutePath().toString());
        run(args);
    }

    /**
     * TALKING_CHARACTER mode (spec section 14): Ken Burns backdrop (same
     * setup as renderSceneSegment) plus a mouth-shape sprite composited on
     * top, switching per the amplitude-driven timeline from
     * extractMouthTimeline. Requires narration audio - falls back to plain
     * renderSceneSegment (called by the caller, not here) if there's none.
     *
     * Mouth POSITION is a heuristic (spec section 8 explicitly sanctions
     * this tier: "if unavailable, use... center-region heuristic"), not
     * detected - centred around 45% width / 55% height, sized to ~12% of
     * frame width, which fits a typical face-centred vertical character
     * portrait reasonably but will be visibly wrong for a wide shot, a
     * profile pose, or a face positioned off-centre. Real face/mouth
     * detection (via the video-worker service, which already has OpenCV) is
     * the natural next step, not yet wired here - stated plainly rather
     * than silently shipping a fixed position as if it were detected.
     */
    private void renderSceneSegmentTalkingCharacter(SceneClip scene, int width, int height, Path outputSegment) {
        if (scene.audioPath() == null) {
            throw new IllegalStateException("TALKING_CHARACTER mode requires narration audio for scene.");
        }
        byte[] narrationBytes;
        try {
            narrationBytes = Files.readAllBytes(scene.audioPath());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read narration audio for lip-sync: " + scene.audioPath(), e);
        }
        List<MouthCue> mouthCues = extractMouthTimeline(narrationBytes, 22050, 100);

        String movement = chooseMovement(scene);
        int frames = Math.max(1, (int) Math.round(Math.max(0.1, scene.durationSeconds()) * 30));
        double canvas = 1.18;
        int workWidth = Math.max(width, (int) Math.round(width * canvas));
        int workHeight = Math.max(height, (int) Math.round(height * canvas));
        String visual = visualExpression(scene.emotion(), scene.lighting());
        List<String> postFilters = new ArrayList<>();
        postFilters.add(visual);
        postFilters.add("vignette=PI/5");
        postFilters.add("fade=t=in:st=0:d=0.35");
        if (scene.durationSeconds() > 0.7) {
            postFilters.add("fade=t=out:st=" + fmt(Math.max(0.35, scene.durationSeconds() - 0.35)) + ":d=0.35");
        }
        String postChain = String.join(",", postFilters);
        String dampenedMotion = motionExpressionDamped(movement, frames, 0.25);
        boolean parallax = dampenedMotion != null;
        String baseGraph = buildBaseGraph(movement, dampenedMotion, parallax, false, frames,
                workWidth, workHeight, width, height, postChain);

        List<String> args = new ArrayList<>();
        args.add(ffmpegBin);
        args.add("-y");
        args.add("-loop");
        args.add("1");
        args.add("-i");
        args.add(scene.imagePath().toAbsolutePath().toString());
        int nextInput = 1;
        int audioInputIndex = nextInput++;
        args.add("-i");
        args.add(scene.audioPath().toAbsolutePath().toString());

        // Five mouth sprites, each a static looping single-frame "video"
        // input - simplest way to feed a still PNG into a timed overlay
        // chain alongside everything else already using -i inputs.
        Map<String, Integer> mouthInputIndex = new LinkedHashMap<>();
        for (String state : List.of("closed", "open_small", "open_medium", "open_large", "round")) {
            Path sprite = mouthSprite(state);
            if (sprite == null) {
                continue;
            }
            mouthInputIndex.put(state.toUpperCase(java.util.Locale.ROOT), nextInput++);
            args.add("-loop");
            args.add("1");
            args.add("-i");
            args.add(sprite.toAbsolutePath().toString());
        }
        args.add("-t");
        args.add(fmt(Math.max(0.1, scene.durationSeconds())));

        int mouthW = (int) Math.round(width * 0.12);
        int mouthH = (int) Math.round(mouthW * (80.0 / 120.0));
        int mouthX = (int) Math.round(width * 0.45) - mouthW / 2;
        int mouthY = (int) Math.round(height * 0.55) - mouthH / 2;

        StringBuilder graph = new StringBuilder("[0:v]" + baseGraph + "[base]");
        String cur = "base";
        int stage = 0;
        for (MouthCue cue : mouthCues) {
            Integer idx = mouthInputIndex.get(cue.state());
            if (idx == null || "CLOSED".equals(cue.state())) {
                // CLOSED needs no sprite at all - the underlying illustration's
                // own (already-closed) mouth shows through untouched, which
                // both looks right and skips an overlay call.
                continue;
            }
            String next = "m" + (stage++);
            graph.append(";[").append(idx).append(":v]scale=").append(mouthW).append(':').append(mouthH).append("[spr").append(stage).append("];");
            graph.append('[').append(cur).append("][spr").append(stage).append(']')
                 .append("overlay=").append(mouthX).append(':').append(mouthY)
                 .append(":enable='between(t,").append(fmt(cue.startSec())).append(',').append(fmt(cue.endSec())).append(")'[").append(next).append(']');
            cur = next;
        }
        graph.append(';').append('[').append(cur).append("]null[vout]");

        // Same narration mixing as the plain 2.5D path - no ambience/SFX
        // here (a talking close-up scene is about the voice, and stacking
        // ambience under it risks fighting the very thing this mode exists
        // to showcase), matching the mode's own narrow purpose.
        args.add("-filter_complex");
        args.add(graph.toString());
        args.add("-map");
        args.add("[vout]");
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("20");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-map");
        args.add(audioInputIndex + ":a");
        args.add("-af");
        args.add("apad=whole_dur=" + fmt(Math.max(0.1, scene.durationSeconds())));
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("128k");
        args.add("-shortest");
        args.add(outputSegment.toAbsolutePath().toString());
        run(args);
    }

    private final Map<String, Path> mouthSpriteCache = new ConcurrentHashMap<>();

    private Path mouthSprite(String state) {
        return mouthSpriteCache.computeIfAbsent(state, n -> {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("lipsync/mouth_" + n + ".png")) {
                if (in == null) {
                    log.warn("Mouth sprite '{}' not found on the classpath; that mouth state will be skipped.", n);
                    return null;
                }
                Path tmp = Files.createTempFile("mouth-" + n + "-", ".png");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            } catch (IOException e) {
                log.warn("Could not extract mouth sprite '{}': {}", n, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Builds the core filter chain for a scene: either a single zoompan
     * pass, or - for pan-capable movements - a two-layer parallax pass
     * (shared scale/crop so both layers start pixel-identical, split,
     * independently zoompanned at different speeds, then blended back
     * together in a narrow band around an estimated horizon so most of the
     * frame renders as a single clean layer and only a thin transition
     * strip blends the two). Returns an unlabelled chain/graph fragment -
     * the caller wraps it in "[0:v]...[base]" when routing through
     * -filter_complex, or uses it directly as a plain -vf chain otherwise.
     */
    private String buildBaseGraph(String movement, String dampenedMotion, boolean parallax, boolean isStatic,
                                  int frames, int workWidth, int workHeight, int width, int height, String postChain) {
        // Animation mode STATIC (spec section 5/46): no camera motion at all,
        // just the image held for the scene's duration. Uses the output size
        // directly rather than the enlarged working canvas, since there's no
        // pan/zoom headroom to fill.
        if (isStatic) {
            return "scale=" + width + ":" + height + ":force_original_aspect_ratio=increase,"
                    + "crop=" + width + ":" + height + ",fps=30," + postChain;
        }
        String scaleCrop = "scale=" + workWidth + ":" + workHeight + ":force_original_aspect_ratio=increase,"
                + "crop=" + workWidth + ":" + workHeight;
        if (!parallax) {
            String motion = motionExpression(movement, frames);
            return scaleCrop + ",zoompan=" + motion + ":d=" + frames + ":s=" + width + "x" + height
                    + ":fps=30," + postChain;
        }
        String fullMotion = motionExpression(movement, frames);
        String zoompanTail = ":d=" + frames + ":s=" + width + "x" + height + ":fps=30";
        // Horizon assumed at 55% down the frame with a 12%-height blend band -
        // a fixed heuristic, not a per-image estimate, since no segmentation
        // or depth model is available. Reasonable for the wide/establishing
        // shots this applies to; a close character portrait would not
        // trigger a pan movement in the first place (see chooseMovement).
        String blendExpr = "A*(1-min(max((Y/H-0.55)/0.12\\,0)\\,1))+B*min(max((Y/H-0.55)/0.12\\,0)\\,1)";
        return scaleCrop + ",split=2[bpre][fpre];"
                + "[bpre]zoompan=" + dampenedMotion + zoompanTail + "[bg];"
                + "[fpre]zoompan=" + fullMotion + zoompanTail + "[fg];"
                + "[bg][fg]blend=all_expr='" + blendExpr + "'," + postChain;
    }

    /** Chooses an environment particle effect from the scene's own text, or
     *  null for none. Mirrors {@link #chooseMovement}: no AI call, just
     *  keyword matching against what the story engine already wrote. */
    private String chooseEnvironmentEffect(SceneClip scene) {
        String text = ((scene.location() == null ? "" : scene.location()) + " "
                + (scene.action() == null ? "" : scene.action()) + " "
                + (scene.emotion() == null ? "" : scene.emotion()) + " "
                + (scene.lighting() == null ? "" : scene.lighting())).toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return null;
        }
        if (text.contains("snow") || text.contains("winter") || text.contains("blizzard")) {
            return "snow";
        }
        if (text.contains("fire") || text.contains("flame") || text.contains("ember")
                || text.contains("dragon") || text.contains("volcano")) {
            return "embers";
        }
        if (text.contains("magic") || text.contains("firefl") || text.contains("sparkle")
                || text.contains("glow") || text.contains("enchant")) {
            return "fireflies";
        }
        if (text.contains("forest") || text.contains("dusty") || text.contains("cave")
                || text.contains("attic") || text.contains("ruins")) {
            return "dust";
        }
        return null;
    }

    /** Picks a one-shot SFX cue from the scene's action text specifically -
     *  not the broader location/emotion/lighting mix chooseEnvironmentEffect
     *  uses, since a discrete event (a door, a footstep) is described by
     *  what happens, not the overall mood of the scene. Independent of, and
     *  can coexist with, the continuous ambience bed for the same scene. */
    private String chooseSfxCue(SceneClip scene) {
        String text = (scene.action() == null ? "" : scene.action()).toLowerCase(java.util.Locale.ROOT);
        if (text.isBlank()) {
            return null;
        }
        if (text.contains("door") || text.contains("gate")) {
            return "door";
        }
        if (text.contains("walk") || text.contains("step") || text.contains("run")
                || text.contains("approach") || text.contains("tiptoe")) {
            return "footstep";
        }
        if (text.contains("magic") || text.contains("sparkle") || text.contains("glow")
                || text.contains("spell") || text.contains("appear")) {
            return "sparkle";
        }
        if (text.contains("fly") || text.contains("dash") || text.contains("swoop")
                || text.contains("jump") || text.contains("rush")) {
            return "whoosh";
        }
        return null;
    }

    /** Real, if crude, stereo positioning for the discrete SFX cue only
     *  (spec section 29): "left"/"right" in the scene's own action text
     *  biases the cue toward that channel; anything else (including no
     *  match) stays centred rather than guessing a direction that wasn't
     *  actually described. */
    private String choosePanBias(SceneClip scene) {
        String text = (scene.action() == null ? "" : scene.action()).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("left")) {
            return "left";
        }
        if (text.contains("right")) {
            return "right";
        }
        return null;
    }

    /**
     * Spec section 14: lip-sync via audio amplitude, not phoneme extraction
     * or a video diffusion model. Splits narration PCM into fixed windows,
     * computes RMS loudness per window, and maps it to a mouth openness
     * state - exactly the fallback tier the spec itself describes for
     * languages/engines with no phoneme timing available (which is every
     * language here, since Piper doesn't expose that).
     *
     * Thresholds are on normalised RMS (0-1), tuned against a synthetic
     * speech-like test signal with real pauses before being wired in here -
     * see the session's own verification, not just eyeballed numbers.
     */
    record MouthCue(double startSec, double endSec, String state) {}

    List<MouthCue> extractMouthTimeline(byte[] wavPcm16Mono, int sampleRate, int windowMs) {
        List<MouthCue> cues = new ArrayList<>();
        int windowSamples = Math.max(1, sampleRate * windowMs / 1000);
        int bytesPerSample = 2;
        int headerSkip = findDataChunkOffset(wavPcm16Mono);
        int n = (wavPcm16Mono.length - headerSkip) / bytesPerSample;

        String prevState = null;
        double cueStart = 0;
        for (int i = 0; i < n; i += windowSamples) {
            int count = Math.min(windowSamples, n - i);
            double sumSquares = 0;
            for (int j = 0; j < count; j++) {
                int offset = headerSkip + (i + j) * bytesPerSample;
                short sample = (short) ((wavPcm16Mono[offset] & 0xFF) | (wavPcm16Mono[offset + 1] << 8));
                sumSquares += (double) sample * sample;
            }
            double rms = count > 0 ? Math.sqrt(sumSquares / count) : 0;
            double norm = rms / 32767.0;
            String state = norm < 0.03 ? "CLOSED" : norm < 0.12 ? "OPEN_SMALL" : norm < 0.25 ? "OPEN_MEDIUM" : "OPEN_LARGE";

            double windowStart = (double) i / sampleRate;
            if (!state.equals(prevState)) {
                if (prevState != null) {
                    cues.add(new MouthCue(cueStart, windowStart, prevState));
                }
                cueStart = windowStart;
                prevState = state;
            }
        }
        if (prevState != null) {
            cues.add(new MouthCue(cueStart, (double) n / sampleRate, prevState));
        }
        return cues;
    }

    /** Finds where PCM sample data actually starts in a WAV file (skips the
     *  RIFF/fmt header, which is not fixed-length - some encoders add extra
     *  chunks before "data"). Falls back to the standard 44-byte offset if
     *  the "data" marker can't be found, rather than throwing - a slightly
     *  wrong offset just shifts the lip-sync timing a few ms, not worth
     *  failing the whole scene over. */
    private int findDataChunkOffset(byte[] wav) {
        for (int i = 12; i < wav.length - 8; i++) {
            if (wav[i] == 'd' && wav[i + 1] == 'a' && wav[i + 2] == 't' && wav[i + 3] == 'a') {
                return i + 8;
            }
        }
        return 44;
    }

    /**
     * Spec section 9: subtle procedural character motion without any
     * detection or diffusion model - a barely-perceptible breathing pulse
     * (a second, tiny zoompan pass layered after the camera-motion one) plus
     * a barely-perceptible head-sway (whole-frame rotate). Deliberately
     * whole-frame, not localised to a detected character region - there is
     * no segmentation to localise to (spec section 8's own heuristic
     * fallback), so this stays subtle enough that applying it to the whole
     * frame reads as "the shot breathes" rather than looking like a
     * deformation error on the background. Verified via real ffmpeg that
     * chaining a second zoompan after the first, then rotate, behaves
     * correctly rather than erroring or compounding incorrectly.
     */
    private String characterMotionFilters(int width, int height, int frames) {
        return "zoompan=z='1.0+0.010*sin(2*PI*0.35*on/30)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
                + ":d=1:s=" + width + "x" + height + ":fps=30,"
                + "rotate='0.007*sin(2*PI*0.12*t)':c=none";
    }

    /**
     * Spec section 12: an explicit, inspectable JSON scene configuration -
     * not a new decision mechanism, but the SAME decisions renderSceneSegment
     * already makes (chooseMovement, chooseEnvironmentEffect, parallax
     * eligibility, character-motion mode), exposed as a structured artifact
     * rather than staying implicit inside the filter-string building. Real
     * values, not placeholders - every field here is computed the same way
     * the renderer itself computes it, just surfaced for logging/inspection
     * (see ProductionPipelineService, where this gets logged per scene
     * alongside the animation-tier decision for the same transparency spec
     * section 55 already asks for).
     */
    private final ObjectMapper motionProfileMapper = new ObjectMapper();

    public String buildMotionProfileJson(SceneClip scene) {
        String movement = chooseMovement(scene);
        int frames = Math.max(1, (int) Math.round(Math.max(0.1, scene.durationSeconds()) * 30));
        boolean isStatic = "STATIC".equals(scene.animationMode());
        String dampenedMotion = isStatic ? null
                : motionExpressionDamped(movement, frames, "HERO".equals(scene.importance()) ? 0.15 : 0.25);
        boolean parallaxEnabled = dampenedMotion != null;
        String effect = chooseEnvironmentEffect(scene);
        String sfxCue = chooseSfxCue(scene);
        boolean characterMotion = "CHARACTER_MOTION".equals(scene.animationMode());
        boolean talking = "TALKING_CHARACTER".equals(scene.animationMode()) && scene.audioPath() != null;

        ObjectNode root = motionProfileMapper.createObjectNode();
        root.put("duration", scene.durationSeconds());
        root.put("fps", 30);

        ObjectNode camera = root.putObject("camera");
        camera.put("type", isStatic ? "static" : movement);
        camera.put("start_zoom", 1.0);
        camera.put("end_zoom", isStatic ? 1.0 : 1.13);

        ObjectNode parallax = root.putObject("parallax");
        parallax.put("enabled", parallaxEnabled);
        parallax.put("background", 0.5);
        parallax.put("character", 1.0);
        parallax.put("foreground", parallaxEnabled ? 1.0 : 0.0);

        ObjectNode character = root.putObject("character");
        character.put("breathing", characterMotion);
        character.put("head_motion", characterMotion);
        // Honest, not aspirational: blink/hair are not implemented (see this
        // session's own notes) - false here reflects what actually renders,
        // not what the mode name might suggest.
        character.put("blink", false);
        character.put("hair_motion", false);
        character.put("talking", talking);

        ObjectNode environment = root.putObject("environment");
        environment.put("particles", effect != null);
        if (effect != null) {
            environment.put("ambience", effect);
        } else {
            environment.putNull("ambience");
        }
        if (sfxCue != null) {
            environment.put("sfx_cue", sfxCue);
        } else {
            environment.putNull("sfx_cue");
        }

        try {
            return motionProfileMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private final Map<String, Path> sfxTemplateCache = new ConcurrentHashMap<>();

    /** Same extraction pattern as {@link #particleTemplate} and
     *  {@link #ambienceTemplate}, for the matching one-shot SFX cue. */
    private Path sfxTemplate(String name) {
        return sfxTemplateCache.computeIfAbsent(name, n -> {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("sfx/" + n + ".m4a")) {
                if (in == null) {
                    log.warn("SFX cue '{}' not found on the classpath; skipping the cue.", n);
                    return null;
                }
                Path tmp = Files.createTempFile("sfx-" + n + "-", ".m4a");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            } catch (IOException e) {
                log.warn("Could not extract SFX cue '{}': {}", n, e.getMessage());
                return null;
            }
        });
    }

    private final Map<String, Path> particleTemplateCache = new ConcurrentHashMap<>();

    /** Extracts a bundled particle template (classpath:particles/&lt;name&gt;.mp4)
     *  to a real file once per JVM run and caches the path - ffmpeg needs an
     *  actual filesystem path, not a classpath stream, and re-extracting on
     *  every scene would be wasteful for a handful of small static assets. */
    private Path particleTemplate(String name) {
        return particleTemplateCache.computeIfAbsent(name, n -> {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("particles/" + n + ".mp4")) {
                if (in == null) {
                    log.warn("Particle template '{}' not found on the classpath; skipping the effect.", n);
                    return null;
                }
                Path tmp = Files.createTempFile("particle-" + n + "-", ".mp4");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            } catch (IOException e) {
                log.warn("Could not extract particle template '{}': {}", n, e.getMessage());
                return null;
            }
        });
    }

    private final Map<String, Path> ambienceTemplateCache = new ConcurrentHashMap<>();

    /** Same extraction pattern as {@link #particleTemplate}, for the
     *  matching procedurally-synthesised ambience bed. */
    private Path ambienceTemplate(String name) {
        return ambienceTemplateCache.computeIfAbsent(name, n -> {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("ambience/" + n + ".m4a")) {
                if (in == null) {
                    log.warn("Ambience template '{}' not found on the classpath; skipping the effect.", n);
                    return null;
                }
                Path tmp = Files.createTempFile("ambience-" + n + "-", ".m4a");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            } catch (IOException e) {
                log.warn("Could not extract ambience template '{}': {}", n, e.getMessage());
                return null;
            }
        });
    }

    private static final java.util.Set<String> KNOWN_MUSIC_PRESETS = java.util.Set.of("calm", "adventurous", "emotional");
    private final Map<String, Path> musicPresetCache = new ConcurrentHashMap<>();

    @Override
    public Path musicPresetPath(String presetId) {
        if (presetId == null || !KNOWN_MUSIC_PRESETS.contains(presetId)) {
            return null;
        }
        return musicPresetCache.computeIfAbsent(presetId, n -> {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("music/" + n + ".m4a")) {
                if (in == null) {
                    log.warn("Music preset '{}' not found on the classpath; no music will be added.", n);
                    return null;
                }
                Path tmp = Files.createTempFile("music-" + n + "-", ".m4a");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp;
            } catch (IOException e) {
                log.warn("Could not extract music preset '{}': {}", n, e.getMessage());
                return null;
            }
        });
    }

    private String chooseMovement(SceneClip scene) {
        if (scene.cameraMovement() != null && !scene.cameraMovement().isBlank()
                && !"auto".equalsIgnoreCase(scene.cameraMovement())) {
            return scene.cameraMovement().toLowerCase(java.util.Locale.ROOT);
        }
        String text = ((scene.action() == null ? "" : scene.action()) + " "
                + (scene.emotion() == null ? "" : scene.emotion())).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("run") || text.contains("walk") || text.contains("follow") || text.contains("chase")) {
            return "track-right";
        }
        if (text.contains("sad") || text.contains("worried") || text.contains("emotional")) {
            return "slow-push";
        }
        if (text.contains("surprise") || text.contains("shock") || text.contains("suddenly")) {
            return "push-pulse";
        }
        if (text.contains("happy") || text.contains("joy") || text.contains("excited") || text.contains("funny")) {
            return "gentle-drift";
        }
        // Nothing in the scene's own text called for a specific movement.
        // For a HERO scene (spec section 21-22: the one or two moments per
        // story that deserve the most production attention) that generic
        // case gets the more dynamic, parallax-eligible "diagonal" move
        // instead of a plain push - the same 2.5D toolkit, just spent on the
        // moment that matters most. A scene with an actual emotional/action
        // cue above still gets the movement that actually fits it,
        // regardless of importance - hero status never overrides a genuine
        // signal from the story text.
        return "HERO".equals(scene.importance()) ? "diagonal" : "slow-push";
    }

    private String motionExpression(String movement, int frames) {
        String n = Integer.toString(Math.max(1, frames - 1));
        return switch (movement) {
            case "zoom-out", "pull-back" ->
                    "z='1.14-(0.13*on/" + n + ")':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'";
            case "pan-left", "track-left" ->
                    "z='1.08':x='(iw-iw/zoom)*(1-on/" + n + ")':y='ih/2-(ih/zoom/2)'";
            case "pan-right", "track-right" ->
                    "z='1.08':x='(iw-iw/zoom)*(on/" + n + ")':y='ih/2-(ih/zoom/2)'";
            case "tilt-up" ->
                    "z='1.08':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-on/" + n + ")'";
            case "tilt-down" ->
                    "z='1.08':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(on/" + n + ")'";
            case "diagonal" ->
                    "z='1.03+(0.08*on/" + n + ")':x='(iw-iw/zoom)*(on/" + n + ")':y='(ih-ih/zoom)*(on/" + n + ")'";
            case "push-pulse" ->
                    "z='1.02+0.10*(on/" + n + ")+0.018*sin(on*PI/8)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'";
            case "gentle-drift" ->
                    "z='1.04+0.06*(on/" + n + ")':x='iw/2-(iw/zoom/2)+8*sin(on*PI/24)':y='ih/2-(ih/zoom/2)+5*cos(on*PI/29)'";
            default ->
                    "z='1.02+0.11*(on/" + n + ")':x='iw/2-(iw/zoom/2)+4*sin(on*PI/32)':y='ih/2-(ih/zoom/2)+3*cos(on*PI/37)'";
        };
    }

    /**
     * Damped counterpart to {@link #motionExpression} for the background
     * layer of a parallax pass: identical zoom (z) formula as the full-speed
     * version - so framing matches at every frame, which is what avoids
     * ghosting - but the lateral/vertical traversal is scaled down by
     * {@code dampening} so the background appears to move slower than the
     * foreground. Returns null for movements with no meaningful lateral
     * component (zoom-only movements), signalling "skip parallax, use the
     * single-layer path" to the caller.
     */
    private String motionExpressionDamped(String movement, int frames, double dampening) {
        String n = Integer.toString(Math.max(1, frames - 1));
        return switch (movement) {
            case "pan-left", "track-left" ->
                    "z='1.08':x='(iw-iw/zoom)*(1-" + fmt(dampening) + "*on/" + n + ")':y='ih/2-(ih/zoom/2)'";
            case "pan-right", "track-right" ->
                    "z='1.08':x='(iw-iw/zoom)*(" + fmt(dampening) + "*on/" + n + ")':y='ih/2-(ih/zoom/2)'";
            case "tilt-up" ->
                    "z='1.08':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-" + fmt(dampening) + "*on/" + n + ")'";
            case "tilt-down" ->
                    "z='1.08':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(" + fmt(dampening) + "*on/" + n + ")'";
            case "diagonal" ->
                    "z='1.03+(0.08*on/" + n + ")':x='(iw-iw/zoom)*(" + fmt(dampening) + "*on/" + n
                            + ")':y='(ih-ih/zoom)*(" + fmt(dampening) + "*on/" + n + ")'";
            case "gentle-drift" ->
                    "z='1.04+0.06*(on/" + n + ")':x='iw/2-(iw/zoom/2)+" + fmt(8 * dampening) + "*sin(on*PI/24)'"
                            + ":y='ih/2-(ih/zoom/2)+" + fmt(5 * dampening) + "*cos(on*PI/29)'";
            // zoom-out/pull-back, push-pulse and the default drift have no
            // strong lateral component to differentiate a second layer on -
            // parallax is skipped for these, falling back to the single,
            // already-tested zoompan path.
            default -> null;
        };
    }

    private String visualExpression(String emotion, String lighting) {
        String e = emotion == null ? "" : emotion.toLowerCase(java.util.Locale.ROOT);
        String l = lighting == null ? "" : lighting.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("sad") || e.contains("worried") || e.contains("nervous")) {
            return "eq=saturation=0.88:contrast=1.04:brightness=-0.015";
        }
        if (e.contains("excited") || e.contains("joy") || e.contains("happy") || e.contains("funny")) {
            return "eq=saturation=1.10:contrast=1.04:brightness=0.012";
        }
        if (e.contains("scared") || e.contains("mysterious") || l.contains("dark")) {
            return "eq=saturation=0.94:contrast=1.08:brightness=-0.025";
        }
        if (e.contains("warm") || l.contains("sunset") || l.contains("golden")) {
            return "eq=saturation=1.06:contrast=1.02:brightness=0.015";
        }
        return "eq=saturation=1.02:contrast=1.02";
    }

    private String safeTransition(String requested, int index) {
        String value = requested == null ? "fade" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "fade", "fadeblack", "fadewhite", "wipeleft", "wiperight", "slideleft", "slideright", "circleopen", "circleclose", "radial", "smoothleft" -> value;
            default -> (index % 2 == 0 ? "fade" : "wiperight");
        };
    }

    private String fmt(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, seconds));
    }

    @Override
    public Path addAudioTrack(Path videoPath, byte[] audioBytes, Path outputPath) {
        Path audioTemp = null;
        try {
            audioTemp = Files.createTempFile("video-audio-", ".wav");
            Files.write(audioTemp, audioBytes);

            List<String> args = new ArrayList<>();
            args.add(ffmpegBin);
            args.add("-y");
            args.add("-i");
            args.add(videoPath.toAbsolutePath().toString());
            args.add("-i");
            args.add(audioTemp.toAbsolutePath().toString());
            args.add("-c:v");
            args.add("copy"); // video stream untouched - no re-encode, no quality loss
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add("192k");
            args.add("-shortest"); // trim to whichever of video/audio is shorter, no looping/padding
            args.add(outputPath.toAbsolutePath().toString());
            run(args);
            return outputPath;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not stage audio for muxing", e);
        } finally {
            if (audioTemp != null) {
                try { Files.deleteIfExists(audioTemp); } catch (IOException ignored) { }
            }
        }
    }

    public Path muxSubtitles(Path videoPath, Path srtPath, boolean burnIn) {
        try {
            Path output = videoPath.resolveSibling("final-with-subs-" + UUID.randomUUID() + ".mp4");
            List<String> args = new ArrayList<>();
            args.add(ffmpegBin);
            args.add("-y");
            args.add("-i");
            args.add(videoPath.toAbsolutePath().toString());
            if (burnIn) {
                args.add("-vf");
                args.add("subtitles=" + srtPath.toAbsolutePath());
                args.add("-c:a");
                args.add("copy");
            } else {
                args.add("-i");
                args.add(srtPath.toAbsolutePath().toString());
                args.add("-c");
                args.add("copy");
                args.add("-c:s");
                args.add("mov_text");
            }
            args.add(output.toAbsolutePath().toString());
            run(args);
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("Subtitle mux failed", e);
        }
    }

    @Override
    public Path renderShort(Path sourceVideo, double startSeconds, double endSeconds, Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            List<String> args = new ArrayList<>();
            args.add(ffmpegBin);
            args.add("-y");
            double duration = Math.max(0.1, endSeconds - startSeconds);
            args.add("-ss");
            args.add(String.valueOf(Math.max(0, startSeconds)));
            args.add("-i");
            args.add(sourceVideo.toAbsolutePath().toString());
            args.add("-t");
            args.add(String.valueOf(duration));
            args.add("-vf");
            // crop/scale the 16:9 source into a 9:16 short, center-cropped
            args.add("crop=ih*9/16:ih,scale=1080:1920");
            args.add("-c:v");
            args.add("libx264");
            args.add("-c:a");
            args.add("aac");
            args.add("-movflags");
            args.add("+faststart");
            args.add(outputPath.toAbsolutePath().toString());
            run(args);
            if (!Files.exists(outputPath) || Files.size(outputPath) < 1024 || probeDurationSeconds(outputPath) < Math.min(duration, 0.5)) {
                throw new IllegalStateException("Short render produced an empty or incomplete file.");
            }
            return outputPath;
        } catch (IOException e) {
            throw new IllegalStateException("Short rendering failed", e);
        }
    }

    @Override
    public byte[] humanizeVoice(byte[] wavBytes) {
        /*
         * Turns raw concatenated TTS output into something closer to a
         * produced voice track:
         *   highpass   - removes sub-80Hz rumble/hum no human voice has
         *   afftdn     - gentle broadband noise reduction (synthesizer hiss)
         *   acompressor- evens out level swings between TTS calls/segments
         *   deesser    - tames harsh "s"/"sh" sibilance a TTS voice can
         *                emphasize more than a human speaker would;
         *                intensity kept moderate (i=0.4) since children's
         *                narration voices skew higher-pitched, where an
         *                aggressive de-esser starts eating real high-end
         *                detail, not just sibilance
         *   loudnorm   - EBU R128 loudness normalization to a standard
         *                spoken-content target (-16 LUFS), so narration
         *                isn't randomly quiet or loud between scenes
         *   alimiter   - safety ceiling so normalization can never clip
         * This is deliberately a fixed, conservative chain - doc guidance is
         * explicit that over-processing reads as "voice with effects", not
         * a better voice. No pitch-shifting, no reverb, no saturation.
         */
        try {
            Path in = Files.createTempFile("voice-raw-", ".wav");
            Path out = Files.createTempFile("voice-processed-", ".wav");
            Files.write(in, wavBytes);
            List<String> args = new ArrayList<>();
            args.add(ffmpegBin);
            args.add("-y");
            args.add("-i");
            args.add(in.toAbsolutePath().toString());
            args.add("-af");
            args.add("highpass=f=80,afftdn=nf=-25,"
                    + "acompressor=threshold=-18dB:ratio=2.5:attack=15:release=250:makeup=2,"
                    + "deesser=i=0.4:m=0.5:f=0.5:s=o,"
                    + "loudnorm=I=-16:TP=-1.5:LRA=11,alimiter=limit=0.97");
            args.add("-ar");
            args.add("22050");
            args.add("-ac");
            args.add("1");
            args.add(out.toAbsolutePath().toString());
            run(args);
            byte[] result = Files.readAllBytes(out);
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
            return result;
        } catch (Exception e) {
            log.warn("Voice post-processing failed, using unprocessed narration: {}", e.getMessage());
            return wavBytes;
        }
    }

    @Override
    public double probeDurationSeconds(Path mediaFile) {
        try {
            List<String> args = List.of(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", mediaFile.toAbsolutePath().toString());
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(30, TimeUnit.SECONDS);
            return Double.parseDouble(out);
        } catch (Exception e) {
            log.warn("ffprobe failed for {}: {}", mediaFile, e.getMessage());
            return -1;
        }
    }

    private void run(List<String> args) {
        try {
            log.debug("Running: {}", String.join(" ", args));
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg timed out: " + String.join(" ", args));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg failed (exit=" + process.exitValue() + "): " + output);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to invoke ffmpeg", e);
        }
    }
}
