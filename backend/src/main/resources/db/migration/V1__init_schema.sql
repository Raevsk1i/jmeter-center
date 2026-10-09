CREATE TABLE ssh_credentials (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    private_key_enc BYTEA NOT NULL,
    passphrase_enc  BYTEA,
    known_hosts     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE generators (
    id                UUID PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    hostname          VARCHAR(255) NOT NULL,
    ssh_port          INT NOT NULL DEFAULT 22,
    ssh_user          VARCHAR(128) NOT NULL DEFAULT 'root',
    ssh_credential_id UUID REFERENCES ssh_credentials(id),
    status            VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
    agent_version     VARCHAR(64),
    java_version      VARCHAR(64),
    jmeter_version    VARCHAR(64),
    cpu_cores         INT,
    ram_mb            BIGINT,
    disk_free_mb      BIGINT,
    cpu_usage_percent DOUBLE PRECISION,
    last_heartbeat_at TIMESTAMPTZ,
    fencing_token     BIGINT NOT NULL DEFAULT 0,
    provision_step    VARCHAR(128),
    provision_error   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_generators_status ON generators(status);
CREATE INDEX idx_generators_heartbeat ON generators(last_heartbeat_at);

CREATE TABLE agent_connections (
    id               UUID PRIMARY KEY,
    generator_id     UUID NOT NULL UNIQUE REFERENCES generators(id) ON DELETE CASCADE,
    agent_id         VARCHAR(128) NOT NULL UNIQUE,
    cert_fingerprint VARCHAR(128),
    session_id       VARCHAR(128),
    registered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE application_settings (
    key        VARCHAR(128) PRIMARY KEY,
    value_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(255),
    action      VARCHAR(128) NOT NULL,
    entity_type VARCHAR(128),
    entity_id   VARCHAR(128),
    detail      JSONB,
    at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE systems (
    id               UUID PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    bitbucket_branch VARCHAR(255) NOT NULL UNIQUE,
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE test_groups (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    parent_id  UUID REFERENCES test_groups(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE test_definitions (
    id                 UUID PRIMARY KEY,
    group_id           UUID REFERENCES test_groups(id),
    system_id          UUID NOT NULL REFERENCES systems(id),
    name               VARCHAR(255) NOT NULL,
    jmx_path           VARCHAR(512) NOT NULL,
    test_type          VARCHAR(32) NOT NULL DEFAULT 'PERF',
    default_properties JSONB NOT NULL DEFAULT '{}',
    description        TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE test_runs (
    id                  UUID PRIMARY KEY,
    test_definition_id  UUID REFERENCES test_definitions(id),
    status              VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    commit_hash         VARCHAR(64),
    configuration       JSONB NOT NULL DEFAULT '{}',
    master_generator_id UUID REFERENCES generators(id),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       TEXT,
    created_by          VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_test_runs_status ON test_runs(status);
CREATE INDEX idx_test_runs_created ON test_runs(created_at DESC);

CREATE TABLE run_generators (
    id           UUID PRIMARY KEY,
    run_id       UUID NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    generator_id UUID NOT NULL REFERENCES generators(id),
    role         VARCHAR(16) NOT NULL,
    UNIQUE (run_id, generator_id)
);

CREATE TABLE generator_reservations (
    id            UUID PRIMARY KEY,
    generator_id  UUID NOT NULL REFERENCES generators(id),
    run_id        UUID NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    role          VARCHAR(16) NOT NULL,
    fencing_token BIGINT NOT NULL,
    window_start  TIMESTAMPTZ NOT NULL,
    window_end    TIMESTAMPTZ,
    status        VARCHAR(32) NOT NULL DEFAULT 'HELD',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_reservation_held_generator
    ON generator_reservations(generator_id)
    WHERE status = 'HELD';

CREATE INDEX idx_reservations_run ON generator_reservations(run_id);

CREATE TABLE scheduled_executions (
    id                 UUID PRIMARY KEY,
    test_definition_id UUID NOT NULL REFERENCES test_definitions(id),
    configuration      JSONB NOT NULL DEFAULT '{}',
    fire_at            TIMESTAMPTZ NOT NULL,
    timezone           VARCHAR(64) NOT NULL DEFAULT 'UTC',
    status             VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    run_id             UUID REFERENCES test_runs(id),
    quartz_job_key     VARCHAR(255),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_scheduled_fire ON scheduled_executions(fire_at);

CREATE TABLE execution_artifacts (
    id            UUID PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    relative_path VARCHAR(1024) NOT NULL,
    sha256        VARCHAR(64) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    stored_path   VARCHAR(2048) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_artifacts_run ON execution_artifacts(run_id);

CREATE TABLE execution_events (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID REFERENCES test_runs(id) ON DELETE CASCADE,
    generator_id UUID REFERENCES generators(id),
    entity_type  VARCHAR(64) NOT NULL,
    entity_id    VARCHAR(128),
    event_type   VARCHAR(128) NOT NULL,
    message      TEXT,
    detail       JSONB,
    at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_run ON execution_events(run_id, at);
CREATE INDEX idx_events_entity ON execution_events(entity_type, entity_id);

CREATE TABLE provisioning_steps (
    id           BIGSERIAL PRIMARY KEY,
    generator_id UUID NOT NULL REFERENCES generators(id) ON DELETE CASCADE,
    step_name    VARCHAR(128) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    message      TEXT,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at  TIMESTAMPTZ
);

CREATE INDEX idx_provision_gen ON provisioning_steps(generator_id);
