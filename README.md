# JMeter Center

Централизованная платформа управления нагрузочным тестированием на базе Apache JMeter.

**Стек:** Java 21 Spring Boot Controller · Go Agents (gRPC) · React/MUI UI · PostgreSQL · Docker Compose

---

## 1. Запуск основного приложения (Docker Compose)

Все команды ниже выполняются из каталога `deploy/`.

### 1.1. Требования

- Docker Engine 24+ и Docker Compose v2
- Свободные порты: `3000` (UI), `8080` (API), `9090` (gRPC), `5432` (PostgreSQL)

### 1.2. Подготовка

```bash
cd deploy
cp .env.example .env   # при необходимости отредактируйте пароль админа
```

Параметры в `.env`:

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `LT_ADMIN_PASSWORD` | Пароль пользователя `admin` | `admin` |
| `LT_MASTER_KEY` | Ключ шифрования секретов в БД (32 символа) | см. `.env.example` |

### 1.3. Старт Controller + PostgreSQL + UI

```bash
cd deploy
docker compose up --build -d
```

Дождитесь готовности:

```bash
docker compose ps
curl -u admin:admin http://localhost:8080/actuator/health
```

Ожидаемый ответ: `{"status":"UP"}`.

### 1.4. Точки входа

| Сервис | URL |
|---|---|
| Web UI | http://localhost:3000 |
| REST API / Swagger | http://localhost:8080/swagger-ui.html |
| gRPC (агенты) | `localhost:9090` (внутри сети Compose: `controller:9090`) |
| PostgreSQL | `localhost:5432` / БД `ltplatform` / пользователь `ltplatform` |

Логин UI/API по умолчанию: **`admin` / `admin`** (или значение `LT_ADMIN_PASSWORD`).

### 1.5. Остановка и логи

```bash
docker compose logs -f controller
docker compose logs -f frontend
docker compose down          # остановить
docker compose down -v       # остановить и удалить данные Postgres/артефакты
```

---

## 2. Настройка и запуск агентов

Агент **сам** устанавливает исходящее gRPC-соединение к Controller. Открывать входящий порт на генераторе не нужно.

Порядок всегда такой:

1. Поднять основное приложение (раздел 1).
2. Создать Generator в UI или API и получить его `id` (UUID).
3. Запустить контейнер агента с этим UUID.

### 2.1. Создание Generator

**Через UI**

1. Откройте http://localhost:3000 → **Settings** → Apply login (`admin` / `admin`).
2. Перейдите в **Generators** → **Add Generator**.
3. Создайте SSH-credential (для Docker-агента SSH не используется — можно указать любой PEM-заглушку).
4. Укажите hostname (например `agent-1`), user `root`, сохраните **без** обязательного SSH-provisioning либо с `provisionNow: false`.
5. Скопируйте UUID генератора со страницы списка.

**Через API**

```bash
# 1) SSH credential (ключ может быть заглушкой, если агент стартует в Docker вручную)
CRED=$(curl -s -u admin:admin -H 'Content-Type: application/json' \
  -d '{"name":"docker-demo","privateKeyPem":"-----BEGIN PRIVATE KEY-----\nSTUB\n-----END PRIVATE KEY-----"}' \
  http://localhost:8080/api/v1/ssh-credentials)
CRED_ID=$(echo "$CRED" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 2) Generator без SSH-provisioning
GEN=$(curl -s -u admin:admin -H 'Content-Type: application/json' \
  -d "{\"name\":\"agent-1\",\"hostname\":\"agent-1\",\"sshPort\":22,\"sshUser\":\"root\",\"sshCredentialId\":\"$CRED_ID\",\"provisionNow\":false}" \
  http://localhost:8080/api/v1/generators)
echo "$GEN" | python3 -m json.tool
# Запомните поле "id"
```

Статус сразу после создания: `PREPARING`. После успешной регистрации агента станет `AVAILABLE`.

### 2.2. Запуск агента через Docker Compose

```bash
cd deploy

# Подставьте UUID из шага 2.1
export DEMO_GENERATOR_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx

docker compose --profile agents up --build -d agent-1
docker compose logs -f agent-1
```

В логах должно появиться:

```text
registered session=... fencing=...
```

Проверка:

```bash
curl -s -u admin:admin http://localhost:8080/api/v1/dashboard | python3 -m json.tool
# onlineAgents >= 1, generators.AVAILABLE >= 1
```

### 2.3. Второй агент (Master + Slave)

```bash
# Создайте второй Generator через UI/API, затем:
export DEMO_GENERATOR_ID_2=yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy

docker compose --profile agents up --build -d agent-2
```

В UI **Execution** выберите одного Master и одного Slave из `AVAILABLE` генераторов.

### 2.4. Переменные окружения агента

| Переменная | Обязательна | Описание |
|---|---|---|
| `LT_CONTROLLER` | да | Адрес gRPC Controller (`controller:9090` в Compose, `host.docker.internal:9090` с хоста) |
| `LT_GENERATOR_ID` | да | UUID Generator из БД/API |
| `LT_AGENT_ID` | нет | Уникальный id агента (по умолчанию `agent-1`) |
| `LT_BOOTSTRAP_TOKEN` | нет | Токен первичной регистрации |
| `LT_JMETER_HOME` | нет | Путь к Apache JMeter (`/opt/lt-jmeter`) |
| `LT_WORK_ROOT` | нет | Рабочие каталоги запусков |
| `LT_STATE_DIR` | нет | Локальный state / command journal |

Образ `Dockerfile.agent` содержит **stub** `jmeter` / `jmeter-server` для проверки оркестрации. Для реальных тестов смонтируйте дистрибутив JMeter:

```yaml
# в docker-compose.yml у сервиса agent-1
volumes:
  - /opt/apache-jmeter-5.6:/opt/lt-jmeter:ro
```

### 2.5. Агент на RHEL (systemd), не в Docker

Controller умеет установить агент по SSH (Generators → Provision). Для ручной установки:

```bash
# На машине с Go или скопируйте бинарь из образа
go build -o lt-agent ./agent/cmd/agent
sudo install -m 755 lt-agent /opt/lt-agent/lt-agent
sudo cp agent/systemd/lt-agent.service /etc/systemd/system/

sudo tee /etc/lt-agent/agent.env <<EOF
LT_CONTROLLER=<IP_или_DNS_controller>:9090
LT_GENERATOR_ID=<uuid>
LT_AGENT_ID=agent-$(hostname)
LT_BOOTSTRAP_TOKEN=demo
LT_JMETER_HOME=/opt/lt-jmeter
LT_WORK_ROOT=/var/lib/lt-agent/workspaces
LT_STATE_DIR=/var/lib/lt-agent/state
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now lt-agent
sudo systemctl status lt-agent
```

Сеть: с хоста генератора должен быть доступен порт **9090/tcp** Controller.

---

## 3. Первичная настройка после старта

### 3.1. Bitbucket (или локальные fixtures)

**Локальный режим (без Bitbucket)** — уже подходит для smoke:

1. UI → **Settings** → Bitbucket:
   - `mode` = `local`
   - `fixtureRoot` = `/fixtures/bitbucket` (том смонтирован в контейнер Controller)
2. Save → **Repository** → **Sync Bitbucket Branches**
3. Создайте Test Group и Test Definition с `jmxPath` = `perf_test/test.jmx`

Эквивалент через API:

```bash
curl -u admin:admin -H 'Content-Type: application/json' -X PUT \
  http://localhost:8080/api/v1/settings/bitbucket \
  -d '{"mode":"local","fixtureRoot":"/fixtures/bitbucket","workspace":"local","repo":"local"}'

curl -u admin:admin -X POST http://localhost:8080/api/v1/bitbucket/sync
```

**Боевой Bitbucket:**

```bash
curl -u admin:admin -H 'Content-Type: application/json' -X PUT \
  http://localhost:8080/api/v1/settings/bitbucket \
  -d '{
    "mode":"remote",
    "baseUrl":"https://api.bitbucket.org/2.0",
    "workspace":"<workspace>",
    "repo":"<repo>",
    "token":"<app-password-or-token>"
  }'
```

### 3.2. Запуск теста

1. Убедитесь, что агент(ы) в статусе `AVAILABLE`.
2. UI → **Execution**: выберите Test, Master, опционально Slaves → **Start Distributed Test**.
3. Статусы и события: **History** / `GET /api/v1/runs/{id}/events`.

Smoke-скрипт (нужны поднятый стек и хотя бы один онлайн-агент):

```bash
# из корня репозитория, при fixtureRoot=/fixtures/bitbucket внутри контейнера
# для скрипта с хоста укажите локальный путь:
FIXTURE_ROOT=/workspace/fixtures/bitbucket ./deploy/scripts/smoke-e2e.sh
```

---

## 4. Архитектура Compose (кратко)

```text
Browser → frontend:3000 → controller:8080 (REST/SSE)
                              │
                         postgres:5432
                              │
                    gRPC :9090 (AgentSession)
                              │
              agent-1 / agent-2 ──► JMeter
```

- PostgreSQL — единственный источник истины (конфиг, резервирование, история).
- Агенты не слушают входящий управляющий порт: только исходящий gRPC к Controller.
- Резервирование генераторов атомарно в БД (fencing token + partial unique index).

---

## 5. Структура репозитория

```text
backend/     Spring Boot Controller
agent/       Go Agent + systemd unit
frontend/    React + TypeScript + MUI
proto/       gRPC/Protobuf
deploy/      docker-compose, Dockerfiles, .env.example
fixtures/    локальные JMX/data/lib для mode=local
docs/        архитектура и acceptance
```

---

## 6. Локальная разработка без Docker (приложения)

1. PostgreSQL 16: БД/пользователь/пароль `ltplatform`.
2. Backend: `cd backend && ./gradlew bootRun`
3. Frontend: `cd frontend && npm install && npm run dev`
4. Agent:

```bash
export LT_CONTROLLER=localhost:9090
export LT_GENERATOR_ID=<uuid>
export LT_AGENT_ID=agent-dev
export LT_JMETER_HOME=/opt/lt-jmeter
go run ./agent/cmd/agent
# или: go build -o deploy/lt-agent ./agent/cmd/agent && ./deploy/lt-agent
```

---

## 7. Тесты

```bash
cd backend && ./gradlew test

# Опционально (нужен Docker для Testcontainers):
RUN_TESTCONTAINERS=true ./gradlew test --tests ReservationServiceTest
```
