-- Voice Director / Audio Director enhancements. Additive only: existing scenes
-- continue to use voice_segments_json and flat audio behaviour when these fields
-- are absent.
ALTER TABLE episode ADD COLUMN narrator_voice_profile_id UUID REFERENCES voice_profile(id) ON DELETE SET NULL;
ALTER TABLE scene ADD COLUMN audio_spec_json TEXT;
