# JMeter Center

Centralized load testing management platform for Apache JMeter.

**Stack:** Java 21 Spring Boot Controller · Go Agents (gRPC/mTLS) · React/MUI UI · PostgreSQL · Docker Compose

## Quick start

```bash
# Build agent binary (used by provisioning / compose volume)
cd agent && go build -o ../deploy/lt-agent ./cmd/agent && cd ..

# Start infrastructure + controller + UI (requires Docker)
cd deploy && docker compose up --build
```

- UI: http://localhost:3000  
- API / Swagger: http://localhost:8080/swagger-ui.html  
- gRPC: localhost:9090  
- Default login: `admin` / `admin`

## Local development (without Docker for apps)

1. Start PostgreSQL 16 with db/user/password `ltplatform`.
2. Backend:

```bash
cd backend
# with Gradle 8.10+
gradle bootRun
```

3. Frontend:

```bash
cd frontend
npm install
npm run dev
```

4. Agent:

```bash
export LT_CONTROLLER=localhost:9090
export LT_GENERATOR_ID=<uuid-from-api>
export LT_AGENT_ID=agent-dev
./deploy/lt-agent
```

## Repository layout

```text
backend/    Spring Boot modular monolith
agent/      Go agent + systemd unit
frontend/   React + TypeScript + MUI
proto/      gRPC/Protobuf contracts
deploy/     Docker Compose + Dockerfiles
docs/       Architecture & acceptance
```

## Tests

```bash
cd backend && gradle test
# Optional Testcontainers race test:
RUN_TESTCONTAINERS=true gradle test --tests ReservationServiceTest
```
