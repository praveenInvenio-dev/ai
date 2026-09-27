-- Per-scene motion/negative prompt for the standalone Video Generation page
-- and per-scene AI video animation (ComfyUIVideoProvider). Generated during
-- story production (see MotionPromptBuilder) so a scene's image already has
-- a matching motion prompt ready to use, rather than the user writing one
-- from scratch every time they want to animate a scene image.
ALTER TABLE scene ADD COLUMN motion_prompt TEXT;
ALTER TABLE scene ADD COLUMN motion_negative_prompt TEXT;
