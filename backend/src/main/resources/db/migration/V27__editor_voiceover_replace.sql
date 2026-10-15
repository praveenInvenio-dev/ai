-- AI video editor: the voiceover REPLACES the original voice (a dub): the original sound is lowered to about 16 % under it
ALTER TABLE video_editor_project ADD COLUMN voiceover_replace BOOLEAN NOT NULL DEFAULT FALSE;
