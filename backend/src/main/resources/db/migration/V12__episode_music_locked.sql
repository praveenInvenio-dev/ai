-- Locks the episode's background music selection (preset or uploaded track)
-- the same way Scene.locked/narrationLocked protect an image or narration:
-- once locked, neither the preset endpoint nor the upload endpoint will
-- replace it until it's unlocked first.
ALTER TABLE episode ADD COLUMN music_locked BOOLEAN NOT NULL DEFAULT FALSE;
