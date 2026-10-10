-- AI video editor: selectable film effects (comma separated ids, e.g. "vignette,film_grain")
ALTER TABLE video_editor_project ADD COLUMN effects VARCHAR(160);
