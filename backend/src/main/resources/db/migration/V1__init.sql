-- AI Story Studio - initial schema

CREATE TABLE provider_configuration (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_type   VARCHAR(64)  NOT NULL,      -- LLM, IMAGE, TTS, MUSIC, SFX, VISION
    provider_name   VARCHAR(128) NOT NULL,      -- ollama, comfyui, piper, mock...
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    config_json     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE universe (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    visual_style_json   TEXT,
    world_rules_json    TEXT,
    color_palette_json  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE story_character (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    universe_id                 UUID REFERENCES universe(id) ON DELETE CASCADE,
    name                        VARCHAR(255) NOT NULL,
    species                     VARCHAR(255),
    age                         VARCHAR(64),
    personality                 TEXT,
    canonical_description       TEXT NOT NULL,
    negative_constraints        TEXT,
    prompt_template             TEXT,
    attributes_json             TEXT,     -- eyes/ears/hair/clothing/accessories/colors etc
    locked                      BOOLEAN NOT NULL DEFAULT FALSE,
    version                     INTEGER NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE character_reference (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    character_id    UUID NOT NULL REFERENCES story_character(id) ON DELETE CASCADE,
    image_path      TEXT NOT NULL,
    image_hash      VARCHAR(128),
    source          VARCHAR(32) NOT NULL DEFAULT 'GENERATED', -- UPLOADED, GENERATED
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE episode_memory (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    universe_id     UUID NOT NULL REFERENCES universe(id) ON DELETE CASCADE,
    episode_id      UUID,
    summary_json    TEXT NOT NULL,   -- events, story_character dev, new locations, objects, unresolved, visual changes
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE episode (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    universe_id         UUID REFERENCES universe(id) ON DELETE SET NULL,
    season_number       INTEGER,
    episode_number       INTEGER,
    title               VARCHAR(255),
    user_prompt         TEXT NOT NULL,
    status              VARCHAR(48) NOT NULL DEFAULT 'DRAFTING',
    duration_target_sec INTEGER,
    target_age          VARCHAR(64),
    genre               VARCHAR(64),
    tone                VARCHAR(64),
    visual_style        VARCHAR(64),
    language             VARCHAR(32) DEFAULT 'English',
    quality_score       INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE story_bible (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    episode_id              UUID NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    version                 INTEGER NOT NULL DEFAULT 1,
    title                   VARCHAR(255),
    logline                 TEXT,
    full_narration          TEXT,
    characters_json         TEXT,
    locations_json          TEXT,
    objects_json            TEXT,
    relationships_json      TEXT,
    timeline_json            TEXT,
    visual_style_json       TEXT,
    continuity_rules_json   TEXT,
    emotional_arc_json      TEXT,
    story_beats_json        TEXT,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scene (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    episode_id          UUID NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    scene_number        INTEGER NOT NULL,
    purpose             TEXT,
    narration           TEXT,
    characters_json      TEXT,
    location            VARCHAR(255),
    action               TEXT,
    emotion             VARCHAR(64),
    camera               VARCHAR(64),
    lighting            VARCHAR(64),
    visual_style        VARCHAR(64),
    continuity_json     TEXT,
    image_prompt        TEXT,
    negative_prompt     TEXT,
    narration_seconds   DOUBLE PRECISION,
    image_duration_seconds DOUBLE PRECISION,
    camera_movement     VARCHAR(32),
    transition_in       VARCHAR(32) DEFAULT 'crossfade',
    order_index          INTEGER NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prompt_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scene_id        UUID NOT NULL REFERENCES scene(id) ON DELETE CASCADE,
    version         INTEGER NOT NULL,
    prompt_type     VARCHAR(32) NOT NULL, -- IMAGE, NEGATIVE, NARRATION
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    episode_id      UUID NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    scene_id        UUID REFERENCES scene(id) ON DELETE CASCADE,
    asset_type      VARCHAR(32) NOT NULL, -- IMAGE, AUDIO_NARRATION, MUSIC, SFX, VIDEO, SUBTITLE, THUMBNAIL, SHORT
    file_path       TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    provider        VARCHAR(64),
    model           VARCHAR(128),
    seed            BIGINT,
    workflow        VARCHAR(128),
    prompt          TEXT,
    negative_prompt TEXT,
    duration_seconds DOUBLE PRECISION,
    metadata_json   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE generation_job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    episode_id      UUID NOT NULL REFERENCES episode(id) ON DELETE CASCADE,
    status          VARCHAR(48) NOT NULL DEFAULT 'QUEUED',
    progress_percent INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE generation_step (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES generation_job(id) ON DELETE CASCADE,
    scene_id        UUID REFERENCES scene(id) ON DELETE SET NULL,
    step_name       VARCHAR(64) NOT NULL,
    provider        VARCHAR(64),
    model           VARCHAR(128),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER NOT NULL DEFAULT 0,
    duration_ms     BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE music_asset (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    file_path       TEXT NOT NULL,
    mood_tags_json  TEXT,
    license         VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sound_effect_asset (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    file_path       TEXT NOT NULL,
    tags_json       TEXT,
    license         VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_universe_project ON universe(project_id);
CREATE INDEX idx_character_universe ON story_character(universe_id);
CREATE INDEX idx_episode_project ON episode(project_id);
CREATE INDEX idx_scene_episode ON scene(episode_id);
CREATE INDEX idx_asset_episode ON asset(episode_id);
CREATE INDEX idx_asset_scene ON asset(scene_id);
CREATE INDEX idx_job_episode ON generation_job(episode_id);
CREATE INDEX idx_step_job ON generation_step(job_id);
