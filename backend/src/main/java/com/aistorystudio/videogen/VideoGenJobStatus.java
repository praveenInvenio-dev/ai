package com.aistorystudio.videogen;

/** Lifecycle of a standalone video-generation job (see {@link VideoGenJob}). */
public enum VideoGenJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
