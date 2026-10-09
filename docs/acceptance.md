# Acceptance Criteria

## Phase 0
- [ ] `docker compose up` starts Postgres + Controller
- [ ] Go agent binary builds
- [ ] React UI builds and loads shell

## Phase 1
- [ ] Flyway migrates schema
- [ ] Admin user seeded
- [ ] Agent registers over gRPC and heartbeats
- [ ] Settings persist encrypted Bitbucket token

## Phase 2
- [ ] Generator wizard creates SSH credential + host
- [ ] Provisioning steps recorded and visible
- [ ] Agent online flips generator to AVAILABLE

## Phase 3
- [ ] Bitbucket branch sync creates Systems
- [ ] Test definitions created with JMX path
- [ ] Execution package built with checksums

## Phase 4
- [ ] Concurrent reservation acquire allows only one winner
- [ ] Distributed start/stop/cleanup works end-to-end
- [ ] Controller restart does not kill running JMeter

## Phase 5
- [ ] Live/historical logs readable from Controller storage
- [ ] Dashboard shows generator/run aggregates

## Phase 6
- [ ] Schedule survives Controller restart
- [ ] Cancel before fire works
