package com.aistorystudio.conceptexplainer.teaching;

import java.util.List;
import java.util.Locale;

/** The teaching stages a lesson can contain. The planner selects, combines, omits or reorders them per topic. */
public enum SectionType {
    HOOK("Hook", "definition", "analogy"),
    ANALOGY("Analogy", "analogy", "analogy_code", "example_list"),
    CONCEPT("Concept", "definition", "table"),
    HOW_IT_WORKS("How it works", "flow", "code_anatomy", "code_visual"),
    EXAMPLE("Example", "code_block", "example_list", "code_visual"),
    REAL_WORLD("Real-world use", "example_list", "flow", "analogy"),
    TRADEOFFS("Trade-offs", "compare", "table"),
    MISTAKES("Common mistakes", "checklist", "compare"),
    PRACTICE("Practice", "checklist", "definition"),
    RECAP("Recap", "summary");

    private final String label;
    private final List<String> templates;

    SectionType(String label, String... templates) {
        this.label = label;
        this.templates = List.of(templates);
    }

    public String label() { return label; }
    /** Templates that normally suit this section (guidance, not a rule). */
    public List<String> templates() { return templates; }

    /** Null when the text is not recognisable. */
    public static SectionType parse(String v) {
        if (v == null || v.isBlank()) return null;
        String k = v.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replace('/', '_');
        for (SectionType s : values()) if (s.name().equals(k)) return s;
        if (k.contains("HOOK") || k.contains("INTRO") || k.contains("OPENING") || k.contains("QUESTION")) return HOOK;
        if (k.contains("ANALOG") || k.contains("METAPHOR")) return ANALOGY;
        if (k.contains("HOW") || k.contains("WALK") || k.contains("STEP") || k.contains("PROCESS") || k.contains("FLOW") || k.contains("MECHANISM")) return HOW_IT_WORKS;
        if (k.contains("REAL") || k.contains("APPLICATION") || k.contains("USE_CASE") || k.contains("PRACTICAL")) return REAL_WORLD;
        if (k.contains("TRADE") || k.contains("PROS") || k.contains("CONS") || k.contains("ADVANTAGE") || k.contains("LIMIT") || k.contains("COMPLEXITY") || k.contains("BEST")) return TRADEOFFS;
        if (k.contains("MISTAKE") || k.contains("PITFALL") || k.contains("MISCONCEPTION") || k.contains("TRAP")) return MISTAKES;
        if (k.contains("PRACTICE") || k.contains("QUIZ") || k.contains("CHALLENGE") || k.contains("CHECK") || k.contains("EXERCISE")) return PRACTICE;
        if (k.contains("RECAP") || k.contains("SUMMARY") || k.contains("CONCLUSION") || k.contains("CLOSING") || k.contains("TAKEAWAY")) return RECAP;
        if (k.contains("EXAMPLE") || k.contains("CODE") || k.contains("DEMO")) return EXAMPLE;
        if (k.contains("CONCEPT") || k.contains("DEFINITION") || k.contains("EXPLAIN") || k.contains("WHAT")) return CONCEPT;
        return null;
    }
}
