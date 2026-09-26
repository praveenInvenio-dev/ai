-- Which background-music mood bed (if any) plays under this episode's video.
-- Null means no music. "custom" is not a stored value here - a user-uploaded
-- MUSIC asset always takes priority over this preset when both exist (see
-- ProductionPipelineService.resolveMusicPath).
ALTER TABLE episode ADD COLUMN music_preset VARCHAR(32);
