-- Per-scene override of the animation decision (spec section 5/46: Auto /
-- 2.5D / Static). AUTO defers to AnimationDecisionService as today. STATIC
-- skips camera motion entirely (a plain still for the scene's duration -
-- useful for text-heavy or intentionally calm scenes). TWO_POINT_FIVE_D
-- forces the full camera-motion+parallax treatment even if the decision
-- service would otherwise have picked something else (moot until local/cloud
-- AI providers are real, but wired up now so it isn't a schema change later).
ALTER TABLE scene ADD COLUMN animation_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';
