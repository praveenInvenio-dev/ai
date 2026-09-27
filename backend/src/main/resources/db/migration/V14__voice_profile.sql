-- Reusable voice profiles (Phase 1 of the Voice Library): a name + reference
-- audio + provider, usable across any story/character rather than being
-- re-entered per episode. "Built-in" profiles (provider=piper) just name an
-- existing Piper voice and have no reference audio; "cloned" profiles
-- (provider=chatterbox, later cosyvoice) carry an uploaded/recorded clip.
CREATE TABLE voice_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    language VARCHAR(20),
    provider VARCHAR(20) NOT NULL,
    -- NULL for a built-in Piper voice (nothing to store - voice_name below
    -- is the actual Piper voice id). Set for a cloned voice: the filename
    -- (no extension) under the shared voice-profile-audio volume, same value
    -- passed as `voice` to the provider's /api/tts call.
    reference_audio_key VARCHAR(80),
    -- For provider=piper: the built-in Piper voice id (e.g. en_US-lessac-high).
    -- For a cloned provider: same as reference_audio_key, kept as its own
    -- column so callers don't need to know which case they're in.
    voice_name VARCHAR(120) NOT NULL,
    personality TEXT,
    metadata_json TEXT,
    duration_seconds DOUBLE PRECISION,
    sample_rate INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_voice_profile_provider ON voice_profile(provider);
