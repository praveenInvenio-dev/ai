package com.aistorystudio.videoeditor.service;

import com.aistorystudio.videoeditor.config.VideoEditorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns every filesystem path the video editor touches.
 *
 * <h2>Why path building is centralised here</h2>
 *
 * Path traversal is prevented structurally rather than by sanitising input.
 * Every filename this class produces is derived from a {@link UUID} plus a
 * validated extension, so there is no code path in which user-supplied text
 * becomes a path segment. The user's original filename is stored in the
 * database as a display label and never reaches the filesystem.
 *
 * {@link #resolveWithin} is the backstop: anything read back out is checked to
 * be inside the project directory after normalisation, so even a corrupted
 * database row cannot escape the storage root.
 */
@Service
public class VideoEditorStorageService {

    private static final Logger log = LoggerFactory.getLogger(VideoEditorStorageService.class);

    private final Path root;

    public VideoEditorStorageService(VideoEditorProperties properties) {
        this.root = Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
    }

    public Path projectDir(UUID projectId) {
        return ensure(root.resolve("projects").resolve(projectId.toString()));
    }

    public Path uploadsDir(UUID projectId)    { return ensure(projectDir(projectId).resolve("uploads")); }
    public Path proxiesDir(UUID projectId)    { return ensure(projectDir(projectId).resolve("proxies")); }
    public Path thumbnailsDir(UUID projectId) { return ensure(projectDir(projectId).resolve("thumbnails")); }
    public Path analysisDir(UUID projectId)   { return ensure(projectDir(projectId).resolve("analysis")); }
    public Path audioDir(UUID projectId)      { return ensure(projectDir(projectId).resolve("audio")); }
    public Path captionsDir(UUID projectId)   { return ensure(projectDir(projectId).resolve("captions")); }
    public Path previewsDir(UUID projectId)   { return ensure(projectDir(projectId).resolve("previews")); }
    public Path rendersDir(UUID projectId)    { return ensure(projectDir(projectId).resolve("renders")); }

    /**
     * Generates the stored filename for an upload: the clip's own UUID plus a
     * lowercased extension drawn from the allowlist. Never the user's filename.
     */
    public String storedFilenameFor(UUID clipId, String extension) {
        return clipId + "." + extension.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a stored filename inside a directory, refusing anything that
     * escapes it.
     *
     * The check is done after normalisation rather than by scanning for ".."
     * because encoded and mixed-separator forms slip past substring checks. If
     * the normalised result is not under the parent, it is rejected outright.
     */
    public Path resolveWithin(Path directory, String storedFilename) {
        Path base = directory.toAbsolutePath().normalize();
        Path resolved = base.resolve(storedFilename).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Refusing to resolve a path outside its project directory");
        }
        return resolved;
    }

    public void write(Path target, java.io.InputStream data) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target.getFileName(), e);
        }
    }

    public void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }

    /** Removes a whole project tree. Used on project delete. */
    public void deleteProject(UUID projectId) {
        Path dir = root.resolve("projects").resolve(projectId.toString()).normalize();
        if (!dir.startsWith(root) || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk {} for deletion: {}", dir, e.getMessage());
        }
    }

    /**
     * Free space on the storage volume, so a render can fail early with a clear
     * message instead of dying part-way through with an FFmpeg I/O error.
     */
    public long usableSpaceBytes() {
        try {
            return Files.getFileStore(ensure(root)).getUsableSpace();
        } catch (IOException e) {
            log.warn("Could not determine free space at {}: {}", root, e.getMessage());
            return Long.MAX_VALUE; // don't block work over a failed probe
        }
    }

    private Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + dir, e);
        }
    }
}
