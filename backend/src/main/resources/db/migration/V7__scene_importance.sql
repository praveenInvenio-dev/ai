-- Scene importance classification (NORMAL / IMPORTANT / HERO), used to decide
-- how much animation budget a scene gets. Today that only means "which 2.5D
-- treatment" (see FFmpegProcessor); the column is deliberately just a plain
-- string rather than an enum tied to a specific animation backend, so a
-- future local/cloud AI video provider can read the same classification
-- without a schema change.
ALTER TABLE scene ADD COLUMN importance VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
