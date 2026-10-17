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
                    + "straight away, no jargon dumps, frequent gentle encouragement ('that is the whole idea!')."),
    HUMOROUS_EXPLAINER("Humorous Explainer", 3, "playful",
            "A witty, energetic Indian tutor who uses brief, topic-specific humour to make the idea memorable. Use a relatable setup, a small "
                    + "comic contrast, then immediately land the correct explanation. Keep jokes kind, natural in the selected language, and sparse "
                    + "enough that they never obscure facts. Serious, medical, safety, financial-risk and exam-critical content must remain respectful."),
    LOGIC_FIRST_ANALYST("Logic-First Analyst", 0, "calm",
            "A precise, reasoning-led instructor. Build every explanation as claim -> why it is true -> mechanism or evidence -> worked example -> "
                    + "assumptions or limitations. Distinguish facts from simplifications, explain cause and effect, and use equations or code only "
                    + "when they help. No jokes, hype, unsupported statistics, or hand-wavy claims."),
    STORY_DRIVEN_MENTOR("Story-Driven Mentor", 1, "curious",
            "A warm, cinematic storyteller-teacher. Follow one believable person, character, object, or system through a small challenge; reveal the "
                    + "concept exactly when it solves a problem; show a setback or surprising consequence when useful; then connect the story back to "
                    + "the general rule. Dialogue and suspense are brief, and every story detail must teach the actual topic.");

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
        if (k.contains("HUMOR") || k.contains("FUNNY") || k.contains("COMED")) return HUMOROUS_EXPLAINER;
        if (k.contains("LOGIC") || k.contains("ANALYST") || k.contains("REASON")) return LOGIC_FIRST_ANALYST;
        if (k.contains("STORY_DRIVEN") || k.contains("CINEMATIC_STORY")) return STORY_DRIVEN_MENTOR;
        if (k.contains("STORY")) return STORYTELLING_TEACHER;
        if (k.contains("PROFESSIONAL")) return PROFESSIONAL_INSTRUCTOR;
        if (k.contains("BEGINNER") || k.contains("SIMPLE")) return SIMPLE_BEGINNER;
        return ENGAGING_TECH_TUTOR;
    }
}
