-- A locked scene's image is never touched by bulk/force regeneration - the
-- same "approve one, keep it, regenerate the rest" workflow already built
-- for the video editor's timeline shots (see timeline_clip.locked), applied
-- here to story scenes.
ALTER TABLE scene ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
