package com.aistorystudio.videoeditor.domain.enums;

/**
 * Output framing. Pixel dimensions live here so nothing downstream parses
 * "9:16" into numbers, and so an unsupported ratio is unrepresentable rather
 * than merely rejected at validation time.
 */
public enum AspectRatio {
    VERTICAL_9_16(1080, 1920),
    LANDSCAPE_16_9(1920, 1080),
    SQUARE_1_1(1080, 1080),
    PORTRAIT_4_5(1080, 1350);

    private final int width;
    private final int height;

    AspectRatio(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() { return width; }
    public int height() { return height; }

    /**
     * Preview dimensions scaled to the configured short edge.
     *
     * Both are forced even because H.264 requires even dimensions; an odd value
     * fails deep inside the filter graph with a message that does not mention
     * dimensions at all, which is a miserable thing to debug.
     */
    public int previewWidth(int shortEdge) {
        return even(Math.round(shortEdge * (width / (float) height)));
    }

    public int previewHeight(int shortEdge) {
        return even(shortEdge);
    }

    private static int even(int value) {
        return value % 2 == 0 ? value : value + 1;
    }
}
