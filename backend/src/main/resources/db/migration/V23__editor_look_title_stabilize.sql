-- AI video editor: CapCut-style look (colour filter), title text overlay and stabilisation per project
ALTER TABLE video_editor_project ADD COLUMN look VARCHAR(32);
ALTER TABLE video_editor_project ADD COLUMN title_text VARCHAR(160);
ALTER TABLE video_editor_project ADD COLUMN stabilize BOOLEAN NOT NULL DEFAULT FALSE;
