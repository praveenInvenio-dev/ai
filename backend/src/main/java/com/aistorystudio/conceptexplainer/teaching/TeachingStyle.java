package com.aistorystudio.conceptexplainer.teaching;

import java.util.Locale;

/** Tone of the narrator. Changes tone and presentation only - alignment rules apply to every style. */
public enum TeachingStyle {
    ENGAGING_TECH_TUTOR("Engaging Tech Tutor", 2, "energetic",
            "Friendly, energetic tech tutor talking to one student. Light, topic-related humour (a quick funny comparison or a tiny two-line "
                    + "character moment) where it helps memory. Everyday Indian-life analogies when they fit naturally (queues, chai stall, cricket, "
                    + "Swiggy/Zomato, UPI, traffic, classroom, office) - never forced."),
    STORYTELLING_TEACHER("Storytelling Teacher", 1, "curious",
            "Warm storyteller. Teach through one small, accurate running story or scenario that the learner follows from the first scene to the "
                    + "recap. Curiosity first ('what happens if...?'), gentle humour only when the story calls for it."),
    PROFESSIONAL_INSTRUCTOR("Professional Instructor", 0, "calm",
            "Clear, composed instructor. Structured and precise, plain intuition first and exact terminology right after. Humour is minimal "
                    + "(at most one light remark). Confident, never stiff."),
    SIMPLE_BEGINNER("Simple Beginner-Friendly", 1, "encouraging",
            "Patient guide for a complete beginner. Shortest possible sentences, one idea at a time, every new term explained in plain words "
                    + "straight away, no jargon dumps, frequent gentle encouragement ('that is the whole idea!').");

    private final String label;
    private final int humour;
    private final String baseEmotion;
    private final String promptFragment;

    TeachingStyle(String label, int humour, String baseEmotion, String promptFragment) {
        this.label = label;
        this.humour = humour;
        this.baseEmotion = baseEmotion;
        this.promptFragment = promptFragment;
    }

    public String label() { return label; }
    /** 0 = almost none, 3 = playful. */
    public int humour() { return humour; }
    public String baseEmotion() { return baseEmotion; }
    public String promptFragment() { return promptFragment; }

    public static TeachingStyle parse(String v) {
        if (v == null || v.isBlank()) return ENGAGING_TECH_TUTOR;
        String k = v.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (TeachingStyle s : values()) if (s.name().equals(k)) return s;
        if (k.contains("STORY")) return STORYTELLING_TEACHER;
        if (k.contains("PROFESSIONAL")) return PROFESSIONAL_INSTRUCTOR;
        if (k.contains("BEGINNER") || k.contains("SIMPLE")) return SIMPLE_BEGINNER;
        return ENGAGING_TECH_TUTOR;
    }
}
