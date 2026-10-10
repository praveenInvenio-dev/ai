package com.aistorystudio.conceptexplainer.teaching;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rough family of the topic - only used to suggest a teaching structure to the planner. */
public enum TopicKind {
    PROGRAMMING("programming concept", true,
            List.of(SectionType.HOOK, SectionType.ANALOGY, SectionType.CONCEPT, SectionType.HOW_IT_WORKS, SectionType.EXAMPLE, SectionType.MISTAKES, SectionType.REAL_WORLD, SectionType.RECAP)),
    SYSTEM_DESIGN("system design / architecture / distributed systems", false,
            List.of(SectionType.HOOK, SectionType.CONCEPT, SectionType.HOW_IT_WORKS, SectionType.REAL_WORLD, SectionType.TRADEOFFS, SectionType.PRACTICE, SectionType.RECAP)),
    ALGORITHM("algorithm / data structure", true,
            List.of(SectionType.HOOK, SectionType.ANALOGY, SectionType.CONCEPT, SectionType.HOW_IT_WORKS, SectionType.EXAMPLE, SectionType.TRADEOFFS, SectionType.PRACTICE, SectionType.RECAP)),
    AI_ML("AI / machine learning", false,
            List.of(SectionType.HOOK, SectionType.ANALOGY, SectionType.CONCEPT, SectionType.HOW_IT_WORKS, SectionType.REAL_WORLD, SectionType.TRADEOFFS, SectionType.RECAP)),
    SCIENCE_EXAM("science / maths / exam topic", false,
            List.of(SectionType.HOOK, SectionType.ANALOGY, SectionType.CONCEPT, SectionType.HOW_IT_WORKS, SectionType.EXAMPLE, SectionType.MISTAKES, SectionType.PRACTICE, SectionType.RECAP)),
    GENERAL("general educational topic", false,
            List.of(SectionType.HOOK, SectionType.ANALOGY, SectionType.CONCEPT, SectionType.HOW_IT_WORKS, SectionType.REAL_WORLD, SectionType.PRACTICE, SectionType.RECAP));

    private final String label;
    private final boolean codeFriendly;
    private final List<SectionType> suggested;

    TopicKind(String label, boolean codeFriendly, List<SectionType> suggested) {
        this.label = label;
        this.codeFriendly = codeFriendly;
        this.suggested = suggested;
    }

    public String label() { return label; }
    /** Whether code / code-anatomy slides normally make sense for this kind of topic. */
    public boolean codeFriendly() { return codeFriendly; }
    public List<SectionType> suggestedSections() { return suggested; }

    private static final Pattern SYSTEM = Pattern.compile("system design|architecture|load balanc|caching|\\bcache\\b|microservice|kafka|rabbitmq|message (queue|broker)|scalab|shard|replicat|\\bcdn\\b|api gateway|rate limit|distributed|consistent hash|\\bredis\\b|\\bdns\\b|\\bhttp\\b|networking|\\btcp\\b|database index|\\bacid\\b|\\bcap theorem\\b|event[- ]driven|pub[/ -]?sub");
    private static final Pattern ALGO = Pattern.compile("algorithm|\\bsorting\\b|binary search|dynamic programming|\\bdp\\b|\\bgraph\\b|linked list|\\bstack\\b|\\bheap\\b|\\bbfs\\b|\\bdfs\\b|big[- ]?o\\b|time complexity|hash ?map|recursion|\\btree\\b|two pointer|sliding window|data structure");
    private static final Pattern AI = Pattern.compile("machine learning|neural|\\bai\\b|artificial intelligence|deep learning|\\bllm\\b|\\bgpt\\b|regression|classification|transformer|reinforcement|computer vision|\\bnlp\\b|prompt engineering");
    private static final Pattern PROG = Pattern.compile("\\bjava\\b|python|javascript|typescript|\\bc\\+\\+|\\bc#|golang|\\brust\\b|kotlin|\\bsql\\b|variable|\\bloop|function|\\bclass\\b|\\bobject\\b|\\barray\\b|\\bstring\\b|\\boop\\b|inherit|polymorphi|exception|pointer|syntax|programming|\\bcode\\b|coding|spring boot|react|angular|\\bgit\\b|\\bapi\\b");
    private static final Pattern SCIENCE = Pattern.compile("physics|chemistry|biology|maths?\\b|mathematics|photosynthesis|newton|\\batom|molecule|\\bcell\\b|genetic|thermodynamic|calculus|trigonometry|\\bforce\\b|\\benergy\\b|\\bwave\\b|electric|magnet|ecosystem|\\bdna\\b|enzyme|ossicle|\\bear\\b");

    public static TopicKind classify(String topic, String track, String subject) {
        String t = track == null ? "" : track.trim().toUpperCase(Locale.ROOT);
        if (t.equals("JEE") || t.equals("NEET")) return SCIENCE_EXAM;
        String s = ((topic == null ? "" : topic) + " " + (subject == null ? "" : subject)).toLowerCase(Locale.ROOT);
        if (SYSTEM.matcher(s).find()) return SYSTEM_DESIGN;
        if (ALGO.matcher(s).find()) return ALGORITHM;
        if (AI.matcher(s).find()) return AI_ML;
        if (PROG.matcher(s).find()) return PROGRAMMING;
        if (SCIENCE.matcher(s).find()) return SCIENCE_EXAM;
        return GENERAL;
    }
}
