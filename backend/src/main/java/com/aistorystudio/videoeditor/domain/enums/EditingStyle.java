package com.aistorystudio.videoeditor.domain.enums;

/**
 * Pacing and technique preference. Orthogonal to category on purpose: a
 * KIDS_STORY can legitimately be cut CINEMATIC or TRENDING_REEL, so collapsing
 * the two into one field would remove a choice users actually want.
 */
public enum EditingStyle {
    CINEMATIC, TRENDING_REEL, KIDS_STORY, BIRTHDAY, VLOG, MUSIC_VIDEO, CUSTOM
}
