package com.aistorystudio.service;

import com.aistorystudio.domain.Scene;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SubtitleService {

    public String buildSrt(List<Scene> orderedScenes) {
        StringBuilder sb = new StringBuilder();
        double cursor = 0;
        int index = 1;
        for (Scene scene : orderedScenes) {
            double duration = scene.getImageDurationSeconds() != null ? scene.getImageDurationSeconds() : 5.0;
            double start = cursor;
            double end = cursor + duration;
            sb.append(index).append("\n");
            sb.append(formatTimestamp(start)).append(" --> ").append(formatTimestamp(end)).append("\n");
            sb.append(scene.getNarration() == null ? "" : scene.getNarration().trim()).append("\n\n");
            cursor = end;
            index++;
        }
        return sb.toString();
    }

    private String formatTimestamp(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        int millis = (int) Math.round((seconds - Math.floor(seconds)) * 1000);
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }
}
