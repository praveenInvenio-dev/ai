package com.aistorystudio.cast;

import java.util.ArrayList;
import java.util.List;

/**
 * One named character in the H3 character-reference cast.
 * <p>
 * The user uploads a photo/drawing once, writes a master prompt, and a clean reference sheet is generated from the
 * upload (identity AND outfit preserved). After that the reference is locked: every H3 clip whose prompt mentions
 * {@link #name} (or an alias) receives exactly this image, so the character never drifts between 5-second scenes.
 * Plain public fields: persisted as JSON by {@link CharacterCastStore}, no DB migration needed.
 */
public class CastCharacter {
    public enum ReferenceStatus { NONE, GENERATING, READY, FAILED }

    public String id;
    public String name;
    /** Extra names the story may use for the same character ("Grandma", "Dadi"). */
    public List<String> aliases = new ArrayList<>();
    /** Master prompt: the fixed description sent with the reference image on every clip. */
    public String masterPrompt = "";
    /** Relative storage paths. */
    public String uploadPath;
    public String referencePath;
    public ReferenceStatus referenceStatus = ReferenceStatus.NONE;
    public String referenceError;
    /** True when the reference sheet is the upload itself (image generation unavailable or chosen by the user). */
    public boolean referenceIsUpload;
    /** Locked references are never regenerated or replaced until the user unlocks them. */
    public boolean locked;
    /** Disabled characters stay in the library but are not sent to H3. */
    public boolean enabled = true;
    /** Attach to every H3 clip even when the clip's prompt does not name the character (e.g. the story's hero). */
    public boolean alwaysInClip;
    /** Character-library copy used by Story-to-H3 keyframes (Qwen) - reused while the reference is unchanged. */
    public String libraryCharacterId;
    public String librarySyncedReference;
    public long createdAt;
    public long updatedAt;

    /** Path H3 should receive, or null when nothing usable exists yet. */
    public String activeReferencePath() {
        if (referenceStatus == ReferenceStatus.READY && referencePath != null) return referencePath;
        return null;
    }
}
