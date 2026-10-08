package com.aistorystudio.conceptexplainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One Concept Explainer lesson (in memory, files under concept-explainers/<id>/). */
public class ConceptExplainerJob {

    public enum Status { QUEUED, PLANNING, GENERATING_SCENES, ASSEMBLING, REGENERATING, SUCCEEDED, FAILED }

    private final UUID id;
    private final String topic, instructions, language, duration, difficulty, motion, model, track, subject, voice;
    private final boolean examFocus;
    private final Instant createdAt = Instant.now();
    private volatile Status status = Status.QUEUED;
    private volatile String stage = "Queued";
    private volatile String errorMessage;
    private volatile String title;
    private volatile String summary;
    private volatile String videoPath;
    private volatile int videoVersion;
    private volatile double totalDurationSeconds;
    private volatile int wordCount;
    private final List<Scene> scenes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    public ConceptExplainerJob(UUID id, String topic, String instructions, String language, String duration,
                               String difficulty, String motion, String model, String track, String subject, boolean examFocus, String voice) {
        this.id = id;
        this.topic = topic;
        this.instructions = instructions;
        this.language = language;
        this.duration = duration;
        this.difficulty = difficulty;
        this.motion = motion;
        this.model = model;
        this.track = track;
        this.subject = subject;
        this.voice = voice == null || voice.isBlank() ? "narrator-male" : voice.trim();
        this.examFocus = examFocus;
    }

    /** One scene = one slide (build steps) + one narration track. */
    public static class Scene {
        private int sceneNumber;
        private String template, title, narration, code, planJson, illustrationPrompt, illustrationPath, audioPath, clipPath;
        private List<String> sentences = new ArrayList<>();
        private List<String> stepPaths = new ArrayList<>();
        private List<Double> stepTimes = new ArrayList<>();
        private double durationSeconds;
        private int imageVersion, audioVersion;

        public int getSceneNumber() { return sceneNumber; }
        public void setSceneNumber(int v) { sceneNumber = v; }
        public String getTemplate() { return template; }
        public void setTemplate(String v) { template = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public String getNarration() { return narration; }
        public void setNarration(String v) { narration = v; }
        public String getCode() { return code; }
        public void setCode(String v) { code = v; }
        /** The scene's raw JSON from the lesson planner (re-rendered on regenerate). */
        public String getPlanJson() { return planJson; }
        public void setPlanJson(String v) { planJson = v; }
        public String getIllustrationPrompt() { return illustrationPrompt; }
        public void setIllustrationPrompt(String v) { illustrationPrompt = v; }
        public String getIllustrationPath() { return illustrationPath; }
        public void setIllustrationPath(String v) { illustrationPath = v; }
        public String getAudioPath() { return audioPath; }
        public void setAudioPath(String v) { audioPath = v; }
        public String getClipPath() { return clipPath; }
        public void setClipPath(String v) { clipPath = v; }
        public List<String> getSentences() { return sentences; }
        public void setSentences(List<String> v) { sentences = v; }
        public List<String> getStepPaths() { return stepPaths; }
        public void setStepPaths(List<String> v) { stepPaths = v; }
        public List<Double> getStepTimes() { return stepTimes; }
        public void setStepTimes(List<Double> v) { stepTimes = v; }
        public double getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(double v) { durationSeconds = v; }
        public int getImageVersion() { return imageVersion; }
        public void bumpImageVersion() { imageVersion++; }
        public int getAudioVersion() { return audioVersion; }
        public void bumpAudioVersion() { audioVersion++; }
        /** Final (complete) slide = last build step. */
        public String getImagePath() { return stepPaths.isEmpty() ? null : stepPaths.get(stepPaths.size() - 1); }
    }

    /** Visual style: neon | sketchnote | storyboard | chalkboard | blueprint | anime. */
    private volatile String style = "neon";
    public String getStyle() { return style; }
    public void setStyle(String v) { style = ConceptSlideRenderer.style(v).id(); }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getInstructions() { return instructions; }
    public String getLanguage() { return language; }
    public String getDuration() { return duration; }
    public String getDifficulty() { return difficulty; }
    /** REVEAL (elements appear with the narration) or STATIC (whole slide at once). */
    public String getMotion() { return motion; }
    public String getModel() { return model; }
    public String getTrack() { return track; }
    public String getSubject() { return subject; }
    public String getVoice() { return voice; }
    public boolean isExamFocus() { return examFocus; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { summary = v; }
    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String v) { videoPath = v; videoVersion++; }
    public int getVideoVersion() { return videoVersion; }
    public double getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(double v) { totalDurationSeconds = v; }
    public int getWordCount() { return wordCount; }
    public void setWordCount(int v) { wordCount = v; }
    public List<Scene> getScenes() { return scenes; }
    public List<String> getWarnings() { return warnings; }
    public boolean isDeepDive() { return duration != null && duration.contains("3"); }
}
