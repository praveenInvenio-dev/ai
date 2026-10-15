package com.aistorystudio.conceptexplainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One Concept Explainer lesson (in memory, files under concept-explainers/<id>/). */
public class ConceptExplainerJob {

    /** STORYBOARD: kids lessons pause here - every scene's picture is ready to review / redo before the video is made. */
    public enum Status { QUEUED, PLANNING, PLAN_REVIEW, GENERATING_SCENES, STORYBOARD, ASSEMBLING, REGENERATING, SUCCEEDED, FAILED }

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
        // adaptive teaching framework: why this scene exists and how it should sound
        private String sectionType, learningObjective, visualDescription, voiceEmotion, voicePace, voiceDelivery;

        public String getSectionType() { return sectionType; }
        public void setSectionType(String v) { sectionType = v; }
        public String getLearningObjective() { return learningObjective; }
        public void setLearningObjective(String v) { learningObjective = v; }
        public String getVisualDescription() { return visualDescription; }
        public void setVisualDescription(String v) { visualDescription = v; }
        public String getVoiceEmotion() { return voiceEmotion; }
        public String getVoicePace() { return voicePace; }
        public String getVoiceDelivery() { return voiceDelivery; }
        public void setVoiceDirection(String emotion, String pace, String delivery) { voiceEmotion = emotion; voicePace = pace; voiceDelivery = delivery; }
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
        /** Tutorial format: ffmpeg image-sequence pattern of the scene's opening motion (glide / zoom / fade), or null. */
        private String introPattern;
        /** Illustrated (kids) scenes: the full-screen picture animated underneath the overlay frames, or null. */
        private String backdropPath;
        public String getBackdropPath() { return backdropPath; }
        public void setBackdropPath(String v) { backdropPath = v; }
        /** Exact start and spoken length of every sentence inside the scene clip (for word captions). */
        private List<Double> sentenceStarts = new ArrayList<>(), sentenceLengths = new ArrayList<>();
        public List<Double> getSentenceStarts() { return sentenceStarts; }
        public List<Double> getSentenceLengths() { return sentenceLengths; }
        public void setSentenceTiming(List<Double> starts, List<Double> lengths) { sentenceStarts = starts; sentenceLengths = lengths; }
        /** English subtitle per narration sentence (same order/count); empty = use the narration itself. */
        private List<String> captions = new ArrayList<>();
        public List<String> getCaptions() { return captions; }
        public void setCaptions(List<String> v) { captions = v == null ? new ArrayList<>() : v; }
        public String getIntroPattern() { return introPattern; }
        public void setIntroPattern(String v) { introPattern = v; }
        /** Typewriter reveals: "step|image-sequence pattern", played from the moment that step appears. */
        private List<String> typeOverlays = new ArrayList<>();
        public List<String> getTypeOverlays() { return typeOverlays; }
        public void setTypeOverlays(List<String> v) { typeOverlays = v == null ? new ArrayList<>() : v; }

        public String getImagePath() {
            if (previewPath != null) return previewPath;   // illustrated scenes: the picture with its overlays
            return stepPaths.isEmpty() ? null : stepPaths.get(stepPaths.size() - 1);
        }
        private String previewPath, animationPath;
        // voice plan for this scene: a pause after each sentence (seconds), words to stress, the camera idea
        private List<Double> pauseAfter = new ArrayList<>();
        private List<String> emphasis = new ArrayList<>();
        private String camera, deliveryNote;
        public List<Double> getPauseAfter() { return pauseAfter; }
        public void setPauseAfter(List<Double> v) { pauseAfter = v == null ? new ArrayList<>() : v; }
        public List<String> getEmphasis() { return emphasis; }
        public void setEmphasis(List<String> v) { emphasis = v == null ? new ArrayList<>() : v; }
        public String getCamera() { return camera; }
        public void setCamera(String v) { camera = v; }
        public String getDeliveryNote() { return deliveryNote; }
        public void setDeliveryNote(String v) { deliveryNote = v; }
        public String getPreviewPath() { return previewPath; }
        public void setPreviewPath(String v) { previewPath = v; }
        /** Wan animation of this scene's picture ("animated cartoon" videos), or null. */
        public String getAnimationPath() { return animationPath; }
        public void setAnimationPath(String v) { animationPath = v; }
    }

    /** Visual style: neon | sketchnote | storyboard | chalkboard | blueprint | anime. */
    private volatile String style = "neon";
    public String getStyle() { return style; }
    public void setStyle(String v) { style = ConceptSlideRenderer.style(v).id(); }

    /**
     * TUTORIAL (default): reference-video teaching - running example built up as a diagram, one element per sentence,
     * drawn in the chosen visual style. CLASSIC: the older one-slide-per-scene templates with AI artwork.
     */
    private volatile String format = "TUTORIAL";
    public String getFormat() { return format; }
    public void setFormat(String v) {
        String f = v == null ? "" : v.trim().toUpperCase(java.util.Locale.ROOT);
        format = f.equals("CLASSIC") ? "CLASSIC" : f.equals("SHORT") ? "SHORT" : f.equals("BREAKDOWN") ? "BREAKDOWN" : "TUTORIAL";
    }
    /** 60-second vertical short (1080x1920, captions, title + outro cards) - the "60-second content" section. */
    public boolean isShort() { return "SHORT".equals(format); }
    /** 2-minute vertical real-world breakdown in the 3D blueprint look (explain a concept through a product people use). */
    public boolean isBreakdown() { return "BREAKDOWN".equals(format); }
    /** Subject area found while planning (technology, medical, biology, finance, everyday ...) - picks icons and look. */
    private volatile String domain = "technology";
    public String getDomain() { return domain; }
    /** Kids lessons: one exact description of the recurring characters, reused for every illustrated scene. */
    // ---- pre-production: the brief, the creative dials, the chosen concept and the script review gate
    private volatile String audience, outcome, tone, concept;
    private volatile boolean reviewScript;
    private volatile String visualVariance = "MID", motionIntensity = "MID", density = "MID";
    private volatile com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.Report scriptReport;
    public String getAudience() { return audience; }
    public void setAudience(String v) { audience = clean(v, 160); }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { outcome = clean(v, 200); }
    public String getTone() { return tone; }
    public void setTone(String v) { tone = clean(v, 80); }
    public String getConcept() { return concept; }
    public void setConcept(String v) { concept = clean(v, 1500); }
    public boolean isReviewScript() { return reviewScript; }
    public void setReviewScript(boolean v) { reviewScript = v; }
    public String getVisualVariance() { return visualVariance; }
    public String getMotionIntensity() { return motionIntensity; }
    public String getDensity() { return density; }
    public void setDials(String variance, String motion, String dens) { visualVariance = dial(variance); motionIntensity = dial(motion); density = dial(dens); }
    public com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.Report getScriptReport() { return scriptReport; }
    public void setScriptReport(com.aistorystudio.conceptexplainer.tutorial.ScriptDoctor.Report r) { scriptReport = r; }
    private static String dial(String v) { String x = v == null ? "MID" : v.trim().toUpperCase(java.util.Locale.ROOT); return x.equals("LOW") || x.equals("HIGH") ? x : "MID"; }
    private static String clean(String v, int max) { if (v == null) return null; String t = v.replaceAll("\\s+", " ").trim(); return t.isEmpty() ? null : t.length() > max ? t.substring(0, max) : t; }

    /** Animated maths / physics scenes (Manim sidecar): wanted by the creator, and actually available when the plan was made. */
    private volatile boolean useManim = true, manimActive;
    public boolean isUseManim() { return useManim; }
    public void setUseManim(boolean v) { useManim = v; }
    public boolean isManimActive() { return manimActive; }
    public void setManimActive(boolean v) { manimActive = v; }

    private volatile String illustrationCast;
    /** First kids picture: the character reference every later (or redone) picture is drawn from. */
    private volatile String castReferencePath;
    private volatile boolean animatedCartoon;
    public boolean isAnimatedCartoon() { return animatedCartoon; }
    public void setAnimatedCartoon(boolean v) { animatedCartoon = v; }
    public String getCastReferencePath() { return castReferencePath; }
    public void setCastReferencePath(String v) { castReferencePath = v; }
    public String getIllustrationCast() { return illustrationCast; }
    public void setIllustrationCast(String v) { illustrationCast = v; }
    public void setDomain(String v) { domain = v == null || v.isBlank() ? "technology" : v.trim().toLowerCase(java.util.Locale.ROOT); }

    /** ENGAGING_TECH_TUTOR | STORYTELLING_TEACHER | PROFESSIONAL_INSTRUCTOR | SIMPLE_BEGINNER */
    private volatile String teachingStyle = "ENGAGING_TECH_TUTOR";
    /** SOCIAL (subscribe closing) | CLASSROOM */
    private volatile String destination = "SOCIAL";
    public String getTeachingStyle() { return teachingStyle; }
    public void setTeachingStyle(String v) { teachingStyle = com.aistorystudio.conceptexplainer.teaching.TeachingStyle.parse(v).name(); }
    public String getDestination() { return destination; }
    public void setDestination(String v) { destination = com.aistorystudio.conceptexplainer.teaching.Destination.parse(v).name(); }

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
    public boolean isDeepDive() { return duration != null && (duration.contains("3") || duration.toLowerCase().contains("full")); }
}
