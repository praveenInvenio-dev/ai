package com.aistorystudio.videoeditor.editing;

import com.aistorystudio.qa.Loudness;

import java.util.List;

/**
 * One-click export settings per platform (resolution = short side, frame rate, loudness). The picture shape (aspect ratio) belongs to
 * the project, so a preset only tells you when the project's shape is not the platform's usual one.
 */
public final class PlatformPresets {

    private PlatformPresets() { }

    /** @param aspect the usual shape: an AspectRatio enum name; @param shortSide resolution (1080 = 1080p); @param loudness a Loudness target id */
    public record Preset(String id, String label, String aspect, int shortSide, int fps, String loudness, String note) { }

    public static final List<Preset> ALL = List.of(
            new Preset("youtube", "YouTube (16:9)", "LANDSCAPE_16_9", 1080, 30, "social", "1080p, 30 fps, -14 LUFS"),
            new Preset("youtube_shorts", "YouTube Shorts (9:16)", "VERTICAL_9_16", 1080, 30, "social", "vertical 1080 x 1920, up to 3 minutes"),
            new Preset("instagram_reels", "Instagram Reels (9:16)", "VERTICAL_9_16", 1080, 30, "social", "vertical 1080 x 1920"),
            new Preset("tiktok", "TikTok (9:16)", "VERTICAL_9_16", 1080, 30, "social", "vertical 1080 x 1920"),
            new Preset("instagram_feed", "Instagram feed (4:5)", "PORTRAIT_4_5", 1080, 30, "social", "1080 x 1350"),
            new Preset("linkedin", "LinkedIn (1:1)", "SQUARE_1_1", 1080, 30, "social", "square 1080 x 1080"),
            new Preset("classroom", "Classroom / lesson (16:9)", "LANDSCAPE_16_9", 720, 30, "podcast", "720p, -16 LUFS, small files"),
            new Preset("broadcast", "Broadcast (16:9, EBU R128)", "LANDSCAPE_16_9", 1080, 25, "broadcast", "1080p, 25 fps, -23 LUFS"));

    public static Preset byId(String id) {
        if (id == null) return null;
        for (Preset p : ALL) if (p.id().equalsIgnoreCase(id.trim())) return p;
        return null;
    }

    static {
        for (Preset p : ALL) if (Loudness.of(p.loudness()) == null) throw new IllegalStateException("unknown loudness target in preset " + p.id());
    }
}
