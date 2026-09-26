package com.aistorystudio.provider;

import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;

/**
 * Fast, dependency-free placeholder image generator used when DEMO_MODE=true
 * or when no real ImageGenerationProvider is reachable. Draws a colored card
 * with the prompt text so the full pipeline (timing, video assembly, subtitles...)
 * can be exercised without a GPU.
 */
@Component
public class MockImageGenerationProvider implements ImageGenerationProvider {

    @Override
    public ImageGenerationResult generateImage(ImageGenerationRequest request) {
        int width = request.width() > 0 ? request.width() : 1920;
        int height = request.height() > 0 ? request.height() : 1080;
        long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color base = colorFromSeed(seed);
        GradientPaint gradient = new GradientPaint(0, 0, base, width, height, base.darker());
        g.setPaint(gradient);
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(255, 255, 255, 220));
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        drawWrapped(g, "[DEMO IMAGE]", 60, 80, width - 120);

        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        drawWrapped(g, request.prompt(), 60, 160, width - 120);

        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return new ImageGenerationResult(baos.toByteArray(), "png", seed, "mock-image-provider", "mock");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render mock image", e);
        }
    }

    private Color colorFromSeed(long seed) {
        int hue = (int) (Math.abs(seed) % 360);
        return Color.getHSBColor(hue / 360f, 0.45f, 0.85f);
    }

    private void drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth) {
        if (text == null) return;
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int curY = y;
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth) {
                g.drawString(line.toString(), x, curY);
                curY += fm.getHeight();
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            g.drawString(line.toString(), x, curY);
        }
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    @Override
    public String providerName() {
        return "mock-image-provider";
    }
}
