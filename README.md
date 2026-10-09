# JMeter Center

Централизованная платформа управления нагрузочным тестированием на Apache JMeter.

**Стек:** Java 21 Spring Boot Controller · Go Agents (gRPC) · React/MUI UI · PostgreSQL · Docker Compose

Основной сценарий: поднять Controller + UI + Postgres, положить распакованный JMeter в `jmeter/`, добавить RHEL-генераторы через UI (SSH). Агент ставится и подключается **сам** (systemd + SSH reverse tunnel).

---

## 1. Быстрый старт

Все команды Compose — из каталога `deploy/`.

### Требования

- Docker Engine 24+ / Compose v2
- Порты: `3000` (UI), `8080` (API), `9090` (gRPC), `5432` (Postgres)

### Подготовка

```bash
cd deploy
cp .env.example .env   # при необходимости смените LT_ADMIN_PASSWORD / LT_MASTER_KEY
```

Распакуйте Apache JMeter в `jmeter/` (корень репозитория):

```bash
# из корня репозитория
curl -L -o /tmp/jmeter.tgz https://dlcdn.apache.org/jmeter/binaries/apache-jmeter-5.6.3.tgz
mkdir -p jmeter
tar -xzf /tmp/jmeter.tgz -C jmeter --strip-components=1
# ожидается jmeter/bin/jmeter
```

Compose монтирует `jmeter/` в Controller как `/data/jmeter` (`LT_JMETER_BUNDLE`). При SSH-provisioning дерево упаковывается и копируется на генератор в `/opt/lt-jmeter`. Подробнее — [`jmeter/README.md`](jmeter/README.md).

### Запуск основного стека

```bash
cd deploy
docker compose up --build -d
```

Поднимаются только **postgres**, **controller**, **frontend**. Контейнеры агентов **не** стартуют (они за profile `agents`).

```bash
docker compose ps
curl -u admin:${LT_ADMIN_PASSWORD:-admin} http://localhost:8080/actuator/health
# {"status":"UP"}
```

| Сервис | URL |
|---|---|
| Web UI | http://localhost:3000 |
| REST / Swagger | http://localhost:8080/swagger-ui.html |
| gRPC | `localhost:9090` |
| PostgreSQL | `localhost:5432`, БД/user/pass `ltplatform` |

Логин по умолчанию: **`admin` / значение `LT_ADMIN_PASSWORD`**.

```bash
docker compose logs -f controller
docker compose down          # остановить
docker compose down -v       # + удалить volumes
```

---

## 2. Переменные окружения

### 2.1. Файл `deploy/.env` (подстановка Compose)

| Переменная | Куда | Назначение | По умолчанию |
|---|---|---|---|
| `LT_ADMIN_PASSWORD` | controller | Пароль пользователя `admin` | `admin` |
| `LT_MASTER_KEY` | controller | AES-ключ шифрования секретов в БД (32 hex-символа) | см. `.env.example` |
| `LT_CONTROLLER_HOST` | controller | Advertise-хост для ручной/Compose-агентной установки (`ltplatform.controller-advertise-host`). Для SSH-provisioning обычно не обязателен | пусто |
| `DEMO_GENERATOR_ID` | agent-1 | UUID генератора для demo-агента (только `--profile agents`) | пусто |
| `DEMO_GENERATOR_ID_2` | agent-2 | UUID второго demo-агента | пусто |
| `LT_AGENT_ID` / `LT_AGENT_ID_2` | agents | Логический id агента | `agent-1` / `agent-2` |
| `LT_BOOTSTRAP_TOKEN` | agents | Bootstrap-токен регистрации | `demo` |

### 2.2. Controller (внутри контейнера / `application.yml`)

Задаются в Compose или при локальном `bootRun`:

| Переменная | Назначение | По умолчанию / Compose |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | JDBC Postgres | `postgres` / `5432` / `ltplatform` |
| `LT_ARTIFACT_ROOT` | Артефакты прогонов | `/data/artifacts` |
| `LT_LOG_ROOT` | Центральные логи прогонов | `/data/logs` |
| `LT_JMETER_BUNDLE` | Путь к распакованному JMeter или `.tgz` на Controller | `/data/jmeter` |
| `LT_AGENT_BINARY` | Бинарник Go-агента для SSH-установки | `/data/agent/lt-agent` |
| `LT_CERTS_DIR` | Каталог CA / клиентских сертификатов | `/data/certs` |
| `LT_GRPC_PORT` | Порт gRPC AgentSession | `9090` |
| `LT_CONTROLLER_HOST` | Advertise host (Settings может переопределить) | пусто |
| `LT_AGENT_REGISTER_WAIT_SECONDS` | Таймаут ожидания регистрации после provision | `90` |
| `LT_ADMIN_PASSWORD` | Пароль seed-админа | `admin` |
| `LT_MASTER_KEY` | Ключ SecretBox | см. выше |

Также в UI (**Settings → Agent controller address**) можно сохранить `controller.advertiseHost` в БД.

### 2.3. Runtime агента (процесс `lt-agent`)

| Переменная | Обязательно | Назначение |
|---|---|---|
| `LT_CONTROLLER` | да | `host:port` gRPC Controller |
| `LT_GENERATOR_ID` | да | UUID строки Generator |
| `LT_AGENT_ID` | нет | Идентификатор агента |
| `LT_BOOTSTRAP_TOKEN` | нет | Токен при регистрации |
| `LT_WORK_ROOT` | нет | Воркспейсы (`/var/lib/lt-agent/workspaces`) |
| `LT_STATE_DIR` | нет | Состояние / сертификаты |
| `LT_JMETER_HOME` | нет | Каталог JMeter (`/opt/lt-jmeter`) |
| `LT_JAVA_HOME` | нет | Опциональный JAVA_HOME |
| `LT_AGENT_VERSION` | нет | Версия в Register |

При SSH-provisioning Controller пишет systemd unit с `LT_CONTROLLER=127.0.0.1:19090` (reverse tunnel на gRPC Controller).

---

## 3. Генераторы (основной путь)

1. UI → **Resources → Secrets** — добавьте SSH private key (или создайте ключ в визарде).
2. **Generators → Add Generator**:
   - выберите **Use existing key** или **Create new key**;
   - укажите hostname/IP, порт, user;
   - **Provision**.
3. Controller по SSH: ставит Java/JMeter/агент, поднимает systemd, ждёт gRPC-регистрации.
4. Статус станет **AVAILABLE**. Логи установки — **Details** → Diagnostic logs.

Удаление генератора: кнопка **Delete** в списке или на странице Details (нельзя удалить `RUNNING` / `RESERVED`).

Повторное использование одного ключа на разных хостах поддерживается (TOFU host keys per host:port).

---

## 4. Secrets (SSH-ключи)

Раздел **Resources → Secrets**:

- список ключей и счётчик «in use»;
- добавить / изменить имя или PEM / passphrase;
- удалить ключ — если он привязан к генераторам, API вернёт **409** со списком генераторов; сначала удалите или переназначьте генераторы.

API:

- `GET/POST /api/v1/ssh-credentials`
- `PUT/DELETE /api/v1/ssh-credentials/{id}`

---

## 5. Опционально: агенты в Docker Compose

Контейнеры `agent-1` / `agent-2` **не** входят в обычный `docker compose up`. Они нужны, когда нет RHEL-хостов и хочется быстро прогнать Master/Slave локально в той же Docker-сети.

### Зачем

- Локальная отладка оркестрации без SSH.
- Smoke / CI с fixtures.

### Как поднять

1. Создайте Generator **без** SSH-provisioning (нужен любой SSH credential в БД — можно заглушка):

```bash
# credential
CRED_ID=$(curl -s -u admin:admin -H 'Content-Type: application/json' \
  -d '{"name":"docker-stub","privateKeyPem":"-----BEGIN OPENSSH PRIVATE KEY-----\nstub\n-----END OPENSSH PRIVATE KEY-----\n"}' \
  http://localhost:8080/api/v1/ssh-credentials | jq -r .id)

# generator, provisionNow=false
GEN_ID=$(curl -s -u admin:admin -H 'Content-Type: application/json' \
  -d "{\"name\":\"agent-1\",\"hostname\":\"agent-1\",\"sshPort\":22,\"sshUser\":\"root\",\"sshCredentialId\":\"$CRED_ID\",\"provisionNow\":false}" \
  http://localhost:8080/api/v1/generators | jq -r .id)
echo "$GEN_ID"
```

2. Запустите профиль `agents`:

```bash
cd deploy
export DEMO_GENERATOR_ID=$GEN_ID
docker compose --profile agents up --build -d agent-1
```

Агент ходит на `controller:9090` внутри сети Compose; `jmeter/` монтируется в `/opt/lt-jmeter`.

Второй агент: создайте второй Generator, `DEMO_GENERATOR_ID_2=<uuid>`, `docker compose --profile agents up -d agent-2`.

Остановка только агентов:

```bash
docker compose --profile agents stop agent-1 agent-2
```

---

## 6. Архитектура Compose (кратко)

```text
frontend:3000  →  controller:8080 (REST) + gRPC :9090
                     ↕ JDBC
                  postgres:5432

SSH-генератор ←── SSH (provision + reverse tunnel :19090) ──→ controller:9090
Compose agent  ──gRPC──→ controller:9090  (только при --profile agents)
```

- Агенты инициируют исходящий gRPC; входящий порт на генераторе не нужен.
- SSH-provisioning держит reverse tunnel, пока жив Controller (сессия в памяти).

---

## 7. Структура репозитория

| Путь | Назначение |
|---|---|
| `backend/` | Spring Boot Controller |
| `agent/` | Go agent |
| `frontend/` | React UI |
| `proto/` | gRPC контракт |
| `deploy/` | Dockerfiles, Compose, `.env.example` |
| `jmeter/` | Распакованный Apache JMeter |
| `fixtures/` | Local Bitbucket fixtures |
| `docs/` | Доп. заметки |

---

## 8. Локальная разработка без Docker

```bash
# Postgres + схема через Flyway при старте
export DB_HOST=localhost DB_PORT=5432 DB_NAME=ltplatform DB_USER=ltplatform DB_PASSWORD=ltplatform
export LT_JMETER_BUNDLE=../jmeter LT_AGENT_BINARY=./data/agent/lt-agent
cd backend && ./gradlew bootRun

cd frontend && npm install && npm run dev   # http://localhost:5173

cd agent && go build -o lt-agent ./cmd/agent
```

Тесты:

```bash
cd backend && ./gradlew test
```

Smoke (нужен поднятый стек и хотя бы один AVAILABLE агент):

```bash
deploy/scripts/smoke-e2e.sh
```
