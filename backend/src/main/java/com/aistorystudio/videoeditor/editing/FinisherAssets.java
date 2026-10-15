package com.aistorystudio.videoeditor.editing;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Gives FFmpeg real file paths for the overlay font and the built-in sound effects (they live inside the jar). */
@Component
public class FinisherAssets {

    private final Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ai-studio-finisher");

    public synchronized Path fontFile() {
        return extract("concept-fonts/Poppins-ExtraBold.ttf");
    }

    /** sfx id -> file, for the ids that exist on the classpath. */
    public synchronized Map<String, Path> sfxFiles() {
        Map<String, Path> out = new LinkedHashMap<>();
        for (String id : TimelineFinisher.SFX_IDS) {
            Path p = extract("sfx/" + id + ".m4a");            // the recorded ones
            if (p == null) p = extract("sfx/" + id + ".wav");  // the synthesised ones (plain PCM)
            if (p != null) out.put(id, p);
        }
        return out;
    }

    private Path extract(String resource) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(resource.replace('/', '_'));
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                ClassPathResource r = new ClassPathResource(resource);
                if (!r.exists()) return null;
                try (InputStream in = r.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }
}
