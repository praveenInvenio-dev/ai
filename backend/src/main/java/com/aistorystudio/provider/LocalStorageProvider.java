package com.aistorystudio.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local filesystem storage under studio.storage.root (default /data/projects).
 * All paths are normalized and re-validated against the storage root to prevent
 * path traversal regardless of what a caller passes in.
 */
@Component
public class LocalStorageProvider implements StorageProvider {

    private final Path root;

    public LocalStorageProvider(@Value("${studio.storage.root}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path store(String relativePath, byte[] content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file at " + relativePath, e);
        }
    }

    @Override
    public Path resolve(String relativePath) {
        String safeRelative = sanitize(relativePath);
        Path resolved = root.resolve(safeRelative).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal rejected: " + relativePath);
        }
        return resolved;
    }

    @Override
    public InputStream read(String relativePath) {
        try {
            return Files.newInputStream(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String sanitize(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        // strip leading slashes and reject ".." segments outright
        String cleaned = relativePath.replace("\\", "/");
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..")) {
                throw new SecurityException("Path traversal rejected: " + relativePath);
            }
        }
        return cleaned;
    }
}
