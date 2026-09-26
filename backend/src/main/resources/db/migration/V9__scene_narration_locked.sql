-- Independent lock for a scene's narration, separate from the existing
-- `locked` column (which only ever governed the image). A user may want to
-- keep a scene's image but redo its narration, or vice versa - one lock
-- flag covering both would force an all-or-nothing choice neither doc asks
-- for (spec section 41/50: locks are per-asset-type).
ALTER TABLE scene ADD COLUMN narration_locked BOOLEAN NOT NULL DEFAULT FALSE;
