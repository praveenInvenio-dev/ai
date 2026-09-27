-- Phase 4 integration: a Character can be assigned a reusable VoiceProfile
-- (Phase 1), closing the loop the doc's Phase 1 asked for ("the same voice
-- must be reusable across ANY story") - until now VoiceProfile existed but
-- nothing in real narration generation actually looked it up.
ALTER TABLE story_character ADD COLUMN voice_profile_id UUID REFERENCES voice_profile(id) ON DELETE SET NULL;

-- FAST/BALANCED/QUALITY generation profile (Phase 4). Per-episode, not
-- global in .env, so different episodes on the same install can trade speed
-- for quality independently (a quick test vs a real final render).
ALTER TABLE episode ADD COLUMN quality_profile VARCHAR(16) NOT NULL DEFAULT 'BALANCED';
