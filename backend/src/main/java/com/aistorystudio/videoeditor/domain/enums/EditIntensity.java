package com.aistorystudio.videoeditor.domain.enums;

/**
 * How aggressive the cutting and motion should be.
 *
 * KIDS_STORY defaults to BALANCED rather than DYNAMIC: rapid flashing and
 * spinning is unpleasant for young viewers and a real accessibility concern,
 * so the default errs toward calm.
 */
public enum EditIntensity {
    SUBTLE, BALANCED, DYNAMIC, AGGRESSIVE
}
