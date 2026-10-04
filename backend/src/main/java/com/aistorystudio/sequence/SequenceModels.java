package com.aistorystudio.sequence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plain data classes for a multi-scene video sequence. Persisted as sequence.json. */
public final class SequenceModels {

    private SequenceModels() {
    }

    public enum SequenceStatus {
        /** Making the keyframe images (locked characters passed to Qwen). */
        KEYFRAMES_RUNNING,
        /** Keyframes ready; waiting for the user to check faces and start the videos. */
        AWAITING_APPROVAL,
        /** Making the clips one by one. */
        VIDEOS_RUNNING,
        MERGING,
        COMPLETED,
        /** Finished, but one or more scenes failed - retry them, or merge what exists. */
        PARTIAL,
        FAILED,
        CANCELLED
    }

    public enum SceneStep {
        PENDING, KEYFRAME_RUNNING, KEYFRAME_READY, VIDEO_RUNNING, DONE, FAILED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SequenceScene {
        public int index;
        /** What the frame looks like (used for the keyframe image). */
        public String visual;
        /** What moves / camera / audio (used for the video model). */
        public String motion;
        public volatile SceneStep step = SceneStep.PENDING;
        public volatile String error;
        public String keyframeFile;
        public String clipFile;
        public Long keyframeSeed;
        public Long videoSeed;
        public Double clipSeconds;
        /** Seconds actually requested after clamping to the engine's maximum. */
        public Double requestedSeconds;
        public Long videoMillis;
        /** True when the user uploaded this keyframe instead of generating it. */
        public boolean uploadedKeyframe;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VideoSequence {
        public UUID id;
        /** Epoch millis (a plain long: this manifest is written by a bare ObjectMapper without the java.time module). */
        public long createdAtMs = System.currentTimeMillis();
        public String title;
        public String style;
        public List<UUID> characterIds = new ArrayList<>();
        /** WAN_2_2 | WAN_2_2_14B | MINIMAX_H3 */
        public String engine;
        public double secondsPerScene;
        /** vertical | horizontal */
        public String orientation;
        public double crossfadeSeconds;
        /** KEYFRAMES (default: each scene starts from its own locked-character keyframe) or
         *  CHAIN (each scene starts from the last frame of the previous clip). */
        public String continuity;
        public boolean reviewKeyframes;
        public volatile SequenceStatus status = SequenceStatus.KEYFRAMES_RUNNING;
        public volatile String error;
        public String mergedFile;
        public Double mergedSeconds;
        public volatile boolean cancelRequested;
        public List<SequenceScene> scenes = new ArrayList<>();

        @JsonIgnore
        public boolean chain() {
            return "CHAIN".equalsIgnoreCase(continuity);
        }

        @JsonIgnore
        public boolean horizontal() {
            return "horizontal".equalsIgnoreCase(orientation);
        }
    }
}
