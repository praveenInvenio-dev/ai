-- AI video editor: loudness target for the final render (null = off; social | podcast | broadcast, see qa.Loudness)
ALTER TABLE video_editor_project ADD COLUMN loudness VARCHAR(12);
