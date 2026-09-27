ALTER TABLE character_reference ADD COLUMN IF NOT EXISTS locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE character_reference ADD COLUMN IF NOT EXISTS prompt_text TEXT;
CREATE INDEX IF NOT EXISTS idx_character_reference_locked ON character_reference(character_id, locked, is_primary);
