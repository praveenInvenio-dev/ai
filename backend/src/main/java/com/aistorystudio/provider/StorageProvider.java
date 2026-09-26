package com.aistorystudio.provider;

import java.io.InputStream;
import java.nio.file.Path;

public interface StorageProvider {

    /** Store bytes under a relative, project-scoped path and return the resolved absolute path. */
    Path store(String relativePath, byte[] content);

    Path resolve(String relativePath);

    InputStream read(String relativePath);

    boolean exists(String relativePath);

    void delete(String relativePath);
}
