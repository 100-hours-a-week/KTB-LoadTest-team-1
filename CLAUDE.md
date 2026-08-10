# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository overview

`ktb-chat` is a monorepo for a real-time chat application, whose primary purpose (per the repo name) is **load testing**: a Next.js frontend, a Spring Boot Socket.IO backend, and three separate load/e2e-testing tool suites that exercise them.

```
apps/backend/    Spring Boot 4 (Java 25) REST + Socket.IO server
apps/frontend/   Next.js 16 (React 19) app, hybrid App/Pages Router
e2e/             Playwright functional tests + Artillery load-test scenarios (reuse the same "actions")
loadtest/        Standalone Node.js Socket.IO load-test scripts (not part of the pnpm workspace)
scripts/         Root-level helper scripts (LAN IP detection)
```

`pnpm-workspace.yaml` only includes `apps/frontend` and `e2e` — `loadtest/` and `apps/backend` are intentionally separate (own lockfile / own build tool) and must be installed/run from within their own directory.

## Common commands

### Root (orchestrates frontend + backend together)
```bash
make setup          # install deps, generate apps/backend/.env and apps/frontend/.env.local
make dev            # setup, then run frontend (3000) + backend (5001/5002) concurrently
make dev-lan        # like `dev`, but binds to the machine's LAN IP (for testing from other devices)
make detect-private-ip
```
`make dev` writes secrets (`JWT_SECRET`, `ENCRYPTION_KEY`, `ENCRYPTION_SALT`) into `apps/backend/.env` automatically on first run — don't hand-roll these.

### Backend (`apps/backend/`, run via `make`, wraps `./mvnw`)
```bash
make setup-java      # installs/activates Java 25 via SDKMAN (required — mvnw alone won't get you Java 25)
make dev             # dev profile, MongoDB/Redis via Testcontainers
make build           # full build + tests
make test            # ./mvnw test (all tests, needs Docker for Testcontainers)
make test-unit       # ./mvnw -Punit-tests test   — excludes Testcontainers-backed tests, no Docker needed
make test-integration # ./mvnw -Pintegration-tests test — only the Testcontainers-backed tests
```
Ports: HTTP API `5001`, Socket.IO `5002`. Swagger UI at `/api/swagger-ui.html`, Socket.IO AsyncAPI docs at `/api/docs/socketio/index.html`.

The `unit-tests`/`integration-tests` Maven profiles split by an explicit include/exclude list in `pom.xml` (not just an `*IntegrationTest` naming convention) — `AuthControllerTest`, `JwtServiceTest`, `RateLimitServiceTest`, `SessionServiceTest`, and `SocketIOConfigTest` are routed to `integration-tests` by name even though they don't end in `IntegrationTest`. Check that list before assuming a new test class needs Testcontainers.

To run a single backend test: `./mvnw test -Dtest=ClassName#methodName`.

### Frontend (`apps/frontend/`)
```bash
pnpm run dev              # next dev (port fixed at 3000 unless launched via root `pnpm run dev`)
pnpm run build
pnpm run lint
pnpm test                 # vitest run
pnpm exec vitest run path/to/file.test.js   # single test file
pnpm run format
```

### E2E / load tests (`e2e/`)
```bash
pnpm test                       # Playwright, @smoke-tagged tests only
pnpm run test:full              # full Playwright suite
pnpm exec playwright test tests/auth.spec.js   # single file
BASE_URL=http://localhost:3000 pnpm test        # target local dev server instead of the deployed default
cd artillery && make artillery  # Artillery+Playwright load scenarios (PHASE1_ARRIVAL_COUNT, PHASE1_DURATION, etc.)
```
**Both `e2e/` (Playwright) and `e2e/artillery/` default `BASE_URL` to the deployed server, not localhost.** Always pass `BASE_URL` explicitly when testing local changes. For Next.js dev-server targets use `http://localhost:3000`, not `127.0.0.1` — `next dev`'s `allowedDevOrigins` only allows `localhost`, so `127.0.0.1` loads the page but silently blocks subsequent asset/data requests.

### Standalone load tests (`loadtest/`, not in the pnpm workspace — install separately)
```bash
cd loadtest && pnpm install
pnpm test                # jest — offline socket-contract drift check, no server needed
pnpm run test:light | test:medium | test:heavy    # load-test.js presets (single shared room)
pnpm run test:rampup     # ramp-up-test.js (new room every second, tests system breaking point)
pnpm run create-users    # pre-seed test users
```

## Architecture

### Socket.IO contract is spec-driven, and drift is enforced by a jest test with no server
The single source of truth for Socket.IO event names is `apps/backend/src/main/resources/static/api/docs/socketio/asyncapi.yaml`. `loadtest/socket-contract.js` mirrors it as JS constants; `loadtest/__tests__/contract-drift.test.js` statically parses the asyncapi YAML plus `apps/frontend/lib/socket/socketClient.js` and `loadtest/load-test.js`/`ramp-up-test.js` to assert every event literal used by the frontend and load scripts is a subset of the asyncapi contract (never checked for full equality — asyncapi also documents AI-streaming channels the load scripts don't touch). When adding or renaming a Socket.IO event, update the asyncapi doc first, then `socket-contract.js`, then usages — and never use an event-name string literal directly in `load-test.js`/`ramp-up-test.js` (use the `socket-contract.js` constants instead) or the drift test will flag it.

### Frontend is mid-migration from Pages Router to App Router
`apps/frontend/app/` (App Router) currently owns the chat screens; `apps/frontend/pages/` (Pages Router) still owns auth/profile. Don't assume one router for the whole app — check which directory currently owns a route before adding to it:

| Route | Screen | Router |
|---|---|---|
| `/` | login | Pages |
| `/login` | redirects to `/` (compat route) | App |
| `/register` | register | Pages |
| `/profile` | profile | Pages |
| `/chat` | room list | App |
| `/chat/[room]` | chat room | App |
| `/chat/new` | create room | Pages |

Each router has its own provider tree that must stay in sync: `pages/_app.js` (`AuthProvider`/`SocketProvider`/`ThemeProvider`, using `next/router`) vs. `app/providers.js` (`AuthProviderWithRouter`, using `next/navigation`). `contexts/AuthContext.js` exports both `withoutAuth`/`useAuth` (Pages-style) and `AuthProviderWithRouter` (App-style) to support both trees simultaneously.

### Frontend layering
- `services/` — thin API/socket clients (older, Pages-Router-era code: `authService.js`, `fileService.js`, `socket.js`, `axios.js`)
- `lib/` — newer equivalents used by the App Router side (`lib/api/client.js`, `lib/auth/*`, `lib/socket/*`)
- `features/chat/` — domain hooks/views split by concern (`composer/`, `files/`, `messages/`, `room/`, `rooms/`), each hook generally has a colocated `__tests__/`
- Path aliases (`jsconfig.json`): `@/components/*`, `@/hooks/*`, `@/services/*`, `@/utils/*`, `@/features/*`, `@/lib/*`, `@/contexts/*`

### Backend package layout (`apps/backend/src/main/java/com/ktb/chatapp/`)
Standard layering: `controller` (REST), `service` (+ `service/ratelimit`, `service/session` subpackages), `repository` (MongoDB), `security` (JWT/Spring Security), `config`, `dto`/`model`, `validation`, `event` (Spring application events, e.g. `RoomCreatedEvent`/`RoomUpdatedEvent`/`SessionEndedEvent`), `storage` (file storage abstraction: `StoragePort`/`LocalStorage`).

Real-time messaging lives under `websocket/socketio/`: `SocketIOConfig`/`SocketIOEventListener` wire up Netty-socketio; per-event logic is split into single-purpose `handler/` classes (`ChatMessageHandler`, `RoomJoinHandler`, `RoomLeaveHandler`, `MessageReactionHandler`, `MessageReadHandler`, `MessageFetchHandler`, `ConnectionLoginHandler`); AI-assistant streaming responses are isolated under `websocket/socketio/ai/` (`AiService`, `AiStreamHandler`).

Auth/session/rate-limiting are MongoDB-TTL-backed rather than in-memory: `SessionMongoStore`/`SessionRepository` (session TTL is 30 minutes, see `Session.java`), `RateLimitMongoStore`/`RateLimitRepository`. `EncryptionUtil` handles AES-256 field encryption (keyed by the `ENCRYPTION_KEY`/`ENCRYPTION_SALT` env vars).

Required env vars (`apps/backend/.env`, auto-generated by `make setup-env`/`make dev` from `.env.template`): `ENCRYPTION_KEY`, `ENCRYPTION_SALT`, `JWT_SECRET`, `MONGO_URI`, `REDIS_HOST`, `REDIS_PORT`. `OPENAI_API_KEY` is optional — omitting it disables AI features.

### Observability
`apps/backend/docker-compose.o11y.yaml` runs Prometheus + Grafana alongside `make dev` / `docker compose up -d` (Grafana on `:9091` in dev, `:3000` in the prod compose file — don't confuse with the frontend's own `:3000`). App metrics are scraped from `/actuator/prometheus`; Mongo/Redis exporters run alongside.

## Non-obvious conventions
- Never modify `data-testid` attributes in frontend components — `e2e/` tests and Artillery scenarios key off them exactly.
- `e2e/actions/*.js` are pure user-action functions (no assertions); `e2e/tests/*.spec.js` compose actions + assertions; `e2e/artillery/scenarios/*.js` reuse the same `actions` functions for load scenarios — keep this separation when adding new flows.
- The repo is Korean-first: READMEs, code comments, and commit-adjacent docs are largely in Korean.
