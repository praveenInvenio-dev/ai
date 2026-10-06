CREATE TABLE video_sequence_record (
    id UUID PRIMARY KEY,
    project_id UUID REFERENCES project(id) ON DELETE SET NULL,
    episode_id UUID REFERENCES episode(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    manifest_json TEXT NOT NULL
);
CREATE INDEX idx_video_sequence_record_expires_at ON video_sequence_record(expires_at);
CREATE INDEX idx_video_sequence_record_episode_id ON video_sequence_record(episode_id);
