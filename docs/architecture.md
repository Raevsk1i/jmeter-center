# Architecture

Modular monolith Controller (Java 21 / Spring Boot) + lightweight Go Agents + React UI.

## Control plane

- REST / SSE / WebSocket to the Web UI
- PostgreSQL is the source of truth for generators, reservations, runs, schedules
- Quartz JDBC job store for scheduled executions
- gRPC bidirectional `AgentSession` initiated by agents (no inbound management port on generators)

## Data plane

- SSH/SFTP bootstrap only for first-time provisioning
- Artifacts and logs stored on Controller filesystem (`LT_ARTIFACT_ROOT`, `LT_LOG_ROOT`)
- JMeter distributed mode (Master client + Slave servers over RMI)

## Safety

- Partial unique index: one `HELD` reservation per generator
- `SELECT … FOR UPDATE` during acquire
- Per-generator fencing tokens on every command
- Agent command idempotency journal + local flock
- Heartbeat timeout → `OFFLINE` without releasing reservations
- Controller restart reconciles active runs without auto-stopping JMeter

## State machines

See plan Phase 2 (Generator) and Phase 4 (TestRun).
