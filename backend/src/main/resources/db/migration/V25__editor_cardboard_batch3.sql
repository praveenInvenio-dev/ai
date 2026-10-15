-- AI video editor, batch 3 (Cardboard parity):
--   look_intensity  - strength of the colour look / LUT, 0..1 (Cardboard: LUT intensity)
--   overlays_json   - timed overlay track: text titles / lower thirds, zoom moments, sound effects (JSON array)
--   export_*        - final render settings: container, resolution, frame rate
ALTER TABLE video_editor_project ADD COLUMN look_intensity DOUBLE PRECISION NOT NULL DEFAULT 1.0;
ALTER TABLE video_editor_project ADD COLUMN overlays_json TEXT;
ALTER TABLE video_editor_project ADD COLUMN export_format VARCHAR(8);
ALTER TABLE video_editor_project ADD COLUMN export_height INTEGER;
ALTER TABLE video_editor_project ADD COLUMN export_fps INTEGER;
