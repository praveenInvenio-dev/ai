ALTER TABLE story_character ADD COLUMN IF NOT EXISTS episode_id UUID REFERENCES episode(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_character_episode ON story_character(episode_id);
