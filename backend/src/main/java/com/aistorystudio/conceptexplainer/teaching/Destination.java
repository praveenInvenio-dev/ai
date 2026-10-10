package com.aistorystudio.conceptexplainer.teaching;

import java.util.Locale;

/** Where the video will be published. Only SOCIAL gets a subscribe-style closing. */
public enum Destination {
    SOCIAL("Social video (YouTube / Reels)"),
    CLASSROOM("Classroom / internal training");

    private final String label;

    Destination(String label) { this.label = label; }

    public String label() { return label; }

    public static Destination parse(String v) {
        if (v == null || v.isBlank()) return SOCIAL;
        String k = v.trim().toUpperCase(Locale.ROOT);
        return k.startsWith("CLASS") || k.startsWith("INTERNAL") || k.startsWith("TRAIN") ? CLASSROOM : SOCIAL;
    }
}
