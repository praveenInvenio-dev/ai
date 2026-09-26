-- AI Video Editor module (see VIDEO_EDITOR_ARCHITECTURE.md).
--
-- Deliberately self-contained: no foreign keys into the story-pipeline tables
-- (project/episode/scene). The editor is usable without ever creating a story,
-- which is a stated product requirement, so coupling the schemas would make
-- that impossible to enforce. The Story Studio integration (slice 7) copies
-- data in rather than referencing it.
--
-- JSON payloads are TEXT, not JSONB, matching the existing convention
-- (asset.metadata_json). Nothing queries inside these documents; they are
-- read whole by the analysis and planning services, so JSONB's indexing and
-- operator support would buy nothing for the extra Hibernate type mapping.

CREATE TABLE video_editor_project (
    id                  UUID PRIMARY KEY,
    name                TEXT        NOT NULL,
    category            VARCHAR(32) NOT NULL,
    editing_style       VARCHAR(32) NOT NULL,
    intensity           VARCHAR(16) NOT NULL,
    aspect_ratio        VARCHAR(16) NOT NULL,
    -- NULL means "Auto": the planner derives length from available footage
    -- rather than padding or speeding up to hit an arbitrary number.
    target_duration_sec INTEGER,
    custom_instructions TEXT,
    state               VARCHAR(32) NOT NULL,
    error_message       TEXT,
    smart_cuts          BOOLEAN     NOT NULL DEFAULT TRUE,
    beat_sync           BOOLEAN     NOT NULL DEFAULT TRUE,
    smart_transitions   BOOLEAN     NOT NULL DEFAULT TRUE,
    auto_captions       BOOLEAN     NOT NULL DEFAULT FALSE,
    audio_enhancement   BOOLEAN     NOT NULL DEFAULT TRUE,
    smart_reframing     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE video_clip (
    id                UUID PRIMARY KEY,
    project_id        UUID        NOT NULL REFERENCES video_editor_project(id) ON DELETE CASCADE,
    -- Generated on upload. The user's filename is a display label only and
    -- never touches the filesystem, which is what makes path traversal
    -- inexpressible rather than merely filtered.
    stored_filename   TEXT        NOT NULL,
    display_name      TEXT        NOT NULL,
    proxy_filename    TEXT,
    thumbnail_filename TEXT,
    sort_order        INTEGER     NOT NULL DEFAULT 0,
    size_bytes        BIGINT,
    duration_sec      DOUBLE PRECISION,
    width             INTEGER,
    height            INTEGER,
    fps               DOUBLE PRECISION,
    video_codec       VARCHAR(32),
    audio_codec       VARCHAR(32),
    has_audio         BOOLEAN     NOT NULL DEFAULT FALSE,
    analyzed          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_video_clip_project ON video_clip(project_id, sort_order);

CREATE TABLE video_scene (
    id            UUID PRIMARY KEY,
    clip_id       UUID             NOT NULL REFERENCES video_clip(id) ON DELETE CASCADE,
    scene_index   INTEGER          NOT NULL,
    start_sec     DOUBLE PRECISION NOT NULL,
    end_sec       DOUBLE PRECISION NOT NULL,
    motion_score  DOUBLE PRECISION,
    brightness    DOUBLE PRECISION,
    blur_score    DOUBLE PRECISION,
    audio_rms     DOUBLE PRECISION,
    -- 0..1 composite used by the planner to prefer good footage. Stored rather
    -- than recomputed so a plan is reproducible after the weights change.
    quality_score DOUBLE PRECISION,
    shot_type     VARCHAR(32),
    has_speech    BOOLEAN          NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_video_scene_clip ON video_scene(clip_id, scene_index);

CREATE TABLE video_analysis (
    id           UUID PRIMARY KEY,
    clip_id      UUID        NOT NULL UNIQUE REFERENCES video_clip(id) ON DELETE CASCADE,
    payload_json TEXT        NOT NULL,
    analyzer     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE editing_plan (
    id             UUID PRIMARY KEY,
    project_id     UUID        NOT NULL REFERENCES video_editor_project(id) ON DELETE CASCADE,
    edl_json       TEXT        NOT NULL,
    -- Which producer made this plan: RULE_ENGINE alone, or RULE_ENGINE+LLM.
    -- Recorded because the rule engine is the fallback when a local model
    -- returns unparseable JSON, and "why does this plan look plain" needs an
    -- answer that is not guesswork.
    planner        VARCHAR(32) NOT NULL,
    rationale      TEXT,
    -- Hash of the timeline, so an unchanged plan reuses its cached preview
    -- instead of re-rendering on every undo/redo round trip.
    plan_hash      VARCHAR(64) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_editing_plan_project ON editing_plan(project_id, created_at DESC);

CREATE TABLE timeline_clip (
    id              UUID PRIMARY KEY,
    project_id      UUID             NOT NULL REFERENCES video_editor_project(id) ON DELETE CASCADE,
    clip_id         UUID             NOT NULL REFERENCES video_clip(id) ON DELETE CASCADE,
    sort_order      INTEGER          NOT NULL,
    source_start_sec DOUBLE PRECISION NOT NULL,
    source_end_sec   DOUBLE PRECISION NOT NULL,
    -- Technique IDs from TechniqueLibrary, never filter strings. An unknown id
    -- fails validation; this column can never carry an FFmpeg fragment.
    technique_in    VARCHAR(64),
    technique_out   VARCHAR(64),
    transition_sec  DOUBLE PRECISION,
    speed           DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    volume          DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    muted           BOOLEAN          NOT NULL DEFAULT FALSE,
    reason          TEXT
);

CREATE INDEX idx_timeline_clip_project ON timeline_clip(project_id, sort_order);

CREATE TABLE audio_track (
    id              UUID PRIMARY KEY,
    project_id      UUID        NOT NULL REFERENCES video_editor_project(id) ON DELETE CASCADE,
    kind            VARCHAR(16) NOT NULL,
    stored_filename TEXT        NOT NULL,
    display_name    TEXT,
    gain            DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    duck_under_speech BOOLEAN   NOT NULL DEFAULT TRUE,
    bpm             DOUBLE PRECISION,
    beats_json      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE caption_track (
    id              UUID PRIMARY KEY,
    project_id      UUID        NOT NULL UNIQUE REFERENCES video_editor_project(id) ON DELETE CASCADE,
    style_preset    VARCHAR(32) NOT NULL DEFAULT 'CLEAN',
    burn_in         BOOLEAN     NOT NULL DEFAULT TRUE,
    srt_filename    TEXT,
    ass_filename    TEXT,
    transcript_json TEXT
);

CREATE TABLE render_job (
    id                UUID PRIMARY KEY,
    project_id        UUID        NOT NULL REFERENCES video_editor_project(id) ON DELETE CASCADE,
    plan_id           UUID        REFERENCES editing_plan(id) ON DELETE SET NULL,
    -- PREVIEW renders at 480p to a plan-hash-keyed file; FINAL renders at
    -- full resolution from the original uploads, not the proxies.
    kind              VARCHAR(16) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    progress_percent  INTEGER     NOT NULL DEFAULT 0,
    stage             VARCHAR(64),
    output_filename   TEXT,
    error_message     TEXT,
    quality_report_json TEXT,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_render_job_project ON render_job(project_id, created_at DESC);
