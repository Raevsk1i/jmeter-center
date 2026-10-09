CREATE TABLE generator_logs (
    id           BIGSERIAL PRIMARY KEY,
    generator_id UUID NOT NULL REFERENCES generators(id) ON DELETE CASCADE,
    source       VARCHAR(64) NOT NULL,
    level        VARCHAR(16) NOT NULL DEFAULT 'INFO',
    event_type   VARCHAR(128) NOT NULL,
    message      TEXT NOT NULL,
    detail       JSONB,
    at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_generator_logs_gen_at ON generator_logs(generator_id, at DESC);
CREATE INDEX idx_generator_logs_source ON generator_logs(generator_id, source);
