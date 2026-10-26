package com.aistorystudio.provider;

import java.nio.file.Path;
import java.util.List;

/**
 * Character references for the H3 clips rendered on the current thread.
 * <p>
 * Story productions (VideoSequenceService) know which library characters - with their locked reference images - are
 * in the story, but the H3 renderer between them and the video provider only passes a start image. This scope hands
 * those references straight to ComfyUIVideoProvider, which attaches them (Ref2VA) to every H3 clip of the scene.
 * Rendering is synchronous, so a ThreadLocal is enough; always use try-with-resources.
 */
public final class H3ReferenceScope implements AutoCloseable {
    public record Ref(String name, String description, Path image) {}

    private static final ThreadLocal<List<Ref>> CURRENT = new ThreadLocal<>();

    private H3ReferenceScope() {}

    public static H3ReferenceScope open(List<Ref> refs) {
        CURRENT.set(refs == null ? List.of() : List.copyOf(refs));
        return new H3ReferenceScope();
    }

    public static List<Ref> current() {
        List<Ref> l = CURRENT.get();
        return l == null ? List.of() : l;
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
