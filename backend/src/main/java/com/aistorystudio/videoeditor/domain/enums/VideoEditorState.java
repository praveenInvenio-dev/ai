package com.aistorystudio.videoeditor.domain.enums;

/**
 * Project lifecycle for the video editor.
 *
 * Separate from the story pipeline's JobStatus on purpose. That enum has 17
 * values and they are story-specific (GENERATING_SCENES, GENERATING_IMAGES,
 * GENERATING_SHORTS); adding PLAN_READY and PREVIEW_RENDERING to it would leak
 * editor concepts into every exhaustive switch on the story side.
 */
public enum VideoEditorState {
    DRAFT, UPLOADING, UPLOADED, ANALYZING, ANALYZED, PLANNING, PLAN_READY,
    PREVIEW_RENDERING, PREVIEW_READY, RENDERING, COMPLETED, FAILED, CANCELLED
}
