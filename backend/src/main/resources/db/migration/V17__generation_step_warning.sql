-- Separate from error_message on purpose: a step that used a TTS fallback
-- still SUCCEEDED (real audio was produced, just not from the voice the
-- user picked) - errorMessage's existing UI treats its presence as "this
-- step failed", which would be actively misleading here.
ALTER TABLE generation_step ADD COLUMN warning_message TEXT;
