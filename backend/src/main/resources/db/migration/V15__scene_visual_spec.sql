-- Structured cinematic scene specification (Phase 2: Cinematic Story +
-- Visual Intelligence). Stored alongside the existing flat action/location/
-- camera/lighting columns rather than replacing them - this is additive:
-- ScenePromptBuilder uses this when present for a much richer prompt, and
-- falls back to the flat fields when it isn't (an older episode, or an LLM
-- response that didn't populate it), so nothing that already worked breaks.
ALTER TABLE scene ADD COLUMN visual_spec_json TEXT;
