# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git conventions

Do **not** add `Co-Authored-By: Claude ...` trailers to commit messages, and do not add the `🤖 Generated with Claude Code` footer to commits or PR bodies.

### Commit messages

Follow [qoomon's Conventional Commit Messages cheatsheet](https://gist.github.com/qoomon/5dfcdf8eec66a051ecd85625518cfd13). Every commit written here must match it.

```
<type>(<optional scope>)<optional !>: <description>

<optional body>

<optional footer>
```

Special cases:

- initial commit: `chore: init`
- merge commit: `Merge branch '<branch name>'` (the default git merge message)
- revert commit: `Revert "<reverted commit subject line>"` (the default git revert message)

**Types**

- `feat` — adds, adjusts, or removes a feature of the API or UI
- `fix` — fixes an API or UI bug of a preceding `feat` commit
- `refactor` — rewrites or restructures code without altering API or UI behavior
- `perf` — a `refactor` that specifically improves performance
- `style` — code style only (white-space, formatting, semicolons); no behavior change
- `test` — adds missing tests or corrects existing ones
- `docs` — documentation only
- `build` — build tooling, dependencies, project version
- `ops` — infrastructure (IaC), deployment scripts, CI/CD pipelines, backups, monitoring, recovery
- `chore` — everything else (init, `.gitignore`, ...)

In this repo that maps to: `terraform/**` and `.github/workflows/**` → `ops`; `Dockerfile`/`docker-compose.yml`/`pom.xml`/`package.json` → `build`; `README.md`/`CLAUDE.md` → `docs`.

**Scope** — optional, and project-defined (e.g. `api`, `board`, `auth`, `chat`, `i18n`, `terraform`, `ci`). Never use an issue identifier as a scope.

**Breaking changes** — mark with `!` before the colon (`feat(api)!: remove status endpoint`) and describe them in the footer starting with `BREAKING CHANGE: ` when the description alone isn't enough.

**Description** — mandatory; imperative present tense ("change", not "changed"/"changes"); do not capitalize the first letter; no trailing period.

**Body** — optional; imperative present tense; explains motivation and contrasts with previous behavior.

**Footer** — optional unless the commit is breaking; may reference issues (`Closes #123`), and any `BREAKING CHANGE:` note goes here.

Examples:

```
feat: add email notifications on new direct messages
fix(shopping-cart): prevent order an empty shopping cart
perf: decrease memory footprint for determine unique visitors by using HyperLogLog
build(release): bump version to 1.0.0
```

```
feat!: remove ticket list endpoint

refers to JIRA-1337

BREAKING CHANGE: ticket endpoints no longer supports list all entities.
```

## Commands

All backend commands run from `backend/`, all frontend commands from `frontend/`. On Windows use `mvnw.cmd`; on Linux/macOS/CI use `./mvnw`. The wrapper is committed as mode `100644` and `.gitattributes` only pins `/mvnw` at the repo root (the real one is `backend/mvnw`), so Linux consumers have to fix it up first — CI runs `chmod +x backend/mvnw` and the Dockerfile runs `sed -i 's/\r$//' mvnw && chmod +x mvnw`.

### Backend (Java 21 / Spring Boot 3.5.16 / Maven wrapper)

```bash
./mvnw clean package                    # build the jar (target/KanbanProject2-0.0.1-SNAPSHOT.jar)
./mvnw spring-boot:run                  # run on :8080
./mvnw test                             # unit tests
./mvnw clean test jacoco:report         # tests + coverage -> target/site/jacoco/index.html
./mvnw test -Dtest=PublicBundlePathsTest                  # one test class
./mvnw test -Dtest=PublicBundlePathsTest#stillGuardsTheApi  # one test method
./mvnw verify                           # runs the jacoco `check` gate
```

The JaCoCo `check` goal is bound to `verify`, so `package` and `test` skip it. Its per-package LINE
minimum is currently **`0.0`** ([pom.xml](backend/pom.xml)) — the rule is wired up but passes anything,
so treat `verify` as a no-op gate rather than a coverage guarantee until that number is raised.

Test sources live under `backend/src/test/java/.../config/` and cover the security filter chain, the
URL space and the auth rate limiter. There is no service-layer test suite yet.

### Frontend (React 19 / Vite 8 / Jest / Cypress)

```bash
npm ci --legacy-peer-deps    # install — the legacy flag is required, plain `npm ci` fails on peer deps
npm run dev                  # dev server on :5173, proxies API paths to :8080
npm run build                # -> frontend/dist
npm run lint                 # ESLint (flat config in eslint.config.js)
npm test                     # Jest
npm run test:coverage        # Jest + coverage -> frontend/coverage
npx jest src/__tests__/components/Board.test.jsx     # one test file
npx jest -t "renders the board"                      # one test by name
npm run cypress:open         # Cypress interactive
npm run cypress:run          # Cypress headless (alias: npm run test:e2e)
```

Cypress `baseUrl` is `http://localhost:5173`, so the Vite dev server **and** the backend must both be running before E2E tests.

The README documents these same commands; keep the two in sync when a script is renamed.

### Full stack via Docker

```bash
docker-compose up -d      # app on :8080, postgres on :5432
docker-compose down
```

Requires a root `.env` (template: `.env.example`) supplying `SPRING_DATASOURCE_DB`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET_KEY`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `CAPTCHA_SECRET`, `CAPTCHA_ENABLED`, `VITE_RECAPTCHA_SITE_KEY`.

## Architecture

### One deployable, two source trees

This is a monolith, not two services. The Dockerfile builds `frontend/` with Vite, copies `dist/` into `backend/src/main/resources/static/`, then builds the Spring Boot jar — so in production React is served by Spring Boot from the same origin on port 8080, and `api.js` calling relative paths like `/api/columns` just works.

Because the jar serves the bundle, Spring Security has to let the bundle through: `/assets/**` (Vite's hashed JS/CSS) and `/locales/**` (the runtime i18n files) are in `PUBLIC_STATIC_ASSETS` alongside root-level files. Single-segment patterns like `/*.js` do **not** match `/assets/index-<hash>.js` — `*` never crosses a `/` — and getting this wrong serves `index.html` and then 403s the script that boots it. `PublicBundlePathsTest` locks the whole set down; nothing else does, since Jest stubs `fetch` and Cypress runs against the Vite dev server.

In local development the two run separately (`:5173` and `:8080`) and [vite.config.js](frontend/vite.config.js) proxies `/api` (the whole REST surface) and `/ws` (SockJS) to the backend. Because the backend applies the `/api` prefix centrally, a new endpoint needs no proxy change.

**Every REST route is served under `/api`.** [WebConfig](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/WebConfig.java) applies the prefix in one place via `configurePathMatch` + `HandlerTypePredicate.forAnnotation(RestController.class)`, so controllers declare their own mapping (`@RequestMapping("/tasks")`) and are served at `/api/tasks`. **Never write `/api` into a controller mapping** — it would be served at `/api/api/...`; `ApiPathPrefixTest` fails the build if you do. `ChatController` is a plain `@Controller` carrying `@MessageMapping`, so the predicate leaves its STOMP destinations alone.

The prefix exists to keep the API off the paths React Router owns. `App.jsx` serves `/board` and `/users`; before the prefix, `/users` resolved to `UserController` and the page was unreachable on a refresh. Client routes are listed once in [SpaRoutes](backend/src/main/java/pl/myproject/kanbanproject2/config/SpaRoutes.java), which both `WebConfig` (forwards them to `index.html`) and `SecurityConfiguration` (permits them) read. **Adding a `<Route>` to `App.jsx` means adding it to `SpaRoutes.ALL`**, or the deep link 403s.

### Backend layering

Packages are organised **by feature, not by layer**: `task/`, `task/subtask/`, `task/history/`, `user/`, `user/auth/`, `layout/column/`, `layout/row/`, `chat/`, `file/`, with cross-cutting code in `config/` and `exception/`. A feature package holds its own entity, controller, service, repository, mapper and DTOs together. (`controller/` still holds `AuthenticationController`, `ChatController` and `FileController`, which have not been moved into their feature packages.)

Within a feature the flow is controller → service → repository, with `mapper` classes converting entities to DTOs. Conventions worth matching:

- Mappers are `@Component` classes implementing `Function<Entity, EntityDTO>`; services call `mapper::apply`. Entities never leave the service layer except where controllers still accept raw entities as request bodies (e.g. `TaskController.createTask`/`patchTask` take a `Task`).
- DTOs are Java `record`s living beside the feature they describe (`task/TaskDto.java`, `layout/column/ColumnDto.java`) — there is no shared `dto/` package.
- Services throw `EntityNotFoundException`, catch it, and rethrow as `ResponseStatusException`; [GlobalExceptionHandler](backend/src/main/java/pl/myproject/kanbanproject2/exception/GlobalExceptionHandler.java) maps validation failures to 400 bodies.
- Error messages in services are written in Polish; UI strings are translated separately through i18n.

### The board model

`Task` sits at the intersection of a `Column` (workflow stage, horizontal) and an optional `Row` (swimlane, vertical). Both `Column` and `Row` carry `position` and `wipLimit`; `Task` carries `position` within its cell. Tasks also support self-referencing parent/child links, a `SubTask` list, a `Set<String> labels` element collection, many-to-many `users`, and a `deadline`/`expired` pair.

Because the entity is named `Column`, `jakarta.persistence.Column` cannot be imported — entity field annotations are written fully qualified as `@jakarta.persistence.Column(...)`. Keep that pattern when adding fields.

**WIP limits are enforced asymmetrically.** Column and row limits are advisory: the backend stores them, and [Board.jsx](frontend/src/components/Board.jsx) only highlights over-limit cells. Per-user limits are the only ones with a server-side check, via `UserService.checkWipStatus`. Don't assume a column limit will be rejected by the API.

Task completion has a real server rule: `TaskService.canTaskBeCompleted` refuses to complete a task whose parent is still open, and un-completing a task cascades to dependents.

A `@Scheduled(fixedRate = 1800000)` job in `TaskService` sweeps deadlines every 30 minutes to flag expired tasks, enabled by `@EnableScheduling` on the application class.

### Frontend state

Three React contexts, composed in [App.jsx](frontend/src/App.jsx): `AuthProvider` wraps the router; `KanbanProvider` and `ChatProvider` wrap the protected routes.

[KanbanContext.jsx](frontend/src/context/KanbanContext.jsx) is the single source of board state (columns, rows, tasks, users) and owns every mutation plus all HTML5 drag-and-drop handlers. Its pattern throughout: call the API, optimistically update local state, fire a `react-toastify` notification keyed through `t()`, and often follow with `refreshTasks()`/`refreshBoard()` to resync. Reordering issues one `updateTaskPosition` call per item in the affected container. New board behavior belongs here rather than in components.

Drag payloads are typed through `dataTransfer` MIME types — `application/task`, `application/column`, `application/row` — and `handleDrop` branches on which type is present, with a `taskId`/`columnId` plain-text fallback.

### Auth

JWT bearer tokens, stateless sessions, one-hour expiry (`security.jwt.expiration-time`). `/api/auth/**` (signup, login, verify, resend), the health/info actuator endpoints, `/ws/**`, the SPA shell (`/` plus `SpaRoutes.ALL`) and the static bundle are public; everything else — the whole of `/api/**` — requires authentication. Signup goes through an emailed verification code before login works, and login can require a Google reCAPTCHA check.

On the client, [apiInterceptor.js](frontend/src/services/apiInterceptor.js) monkey-patches `window.fetch` at module load to attach `Authorization` from `localStorage`, skipping any URL containing `/auth/` (which still matches the prefixed `/api/auth/...`) and redirecting to `/` on expiry. Because it wraps the global, tests and any code path using `fetch` inherit it.

CORS allowed origins are hardcoded in two places that must stay in sync: [SecurityConfiguration](backend/src/main/java/pl/myproject/kanbanproject2/config/security/SecurityConfiguration.java) and [WebSocketConfig](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/WebSocketConfig.java). They have already drifted — the WebSocket list additionally allows `http://kanbanproject.pl` and `http://www.kanbanproject.pl`.

### Chat

STOMP over SockJS at `/ws`, simple in-memory broker on `/topic` and `/queue`, app prefix `/app`, user prefix `/user`; `WebSocketAuthInterceptor` authenticates the inbound channel. `ChatContext` uses a reducer (not `useState`) and delegates the connection to [chatApi.js](frontend/src/services/chatApi.js), which **hardcodes `http://localhost:8080` as `serverUrl`** — that is a real limitation to be aware of when touching chat, not something the proxy fixes.

### Configuration and secrets

`application.properties` resolves everything from environment variables and imports `optional:file:.env[.properties]`, so a `.env` in the backend working directory supplies local values (template: `backend/.env.example`). `KanbanConfig` additionally loads dotenv directly via `io.github.cdimascio:dotenv-java`. `.env` files are gitignored.

`spring.jpa.hibernate.ddl-auto=update` means Hibernate owns the schema at runtime. `backend/db.sql` is only mounted as a Postgres init script by docker-compose and covers a small subset of the tables — it is not a migration system, so schema changes come from entity edits, and there is no Flyway/Liquibase here.

Captcha is currently frontend-only: the widget renders if `VITE_RECAPTCHA_SITE_KEY` was baked in at Vite build time (it is a Docker `ARG`), and `authService.js` sends the token as `captcha: { token }`, but no backend code reads it — `CAPTCHA_SECRET` / `CAPTCHA_ENABLED` are plumbed through docker-compose and Terraform to a server-side check that does not exist yet, so the token is silently ignored.

### CI/CD and infrastructure

- `kanban-ci.yml` — on PRs and pushes to `main`: backend job runs `mvnw clean test jacoco:report` against a Postgres service container (writing a `.env` from secrets first); frontend job builds, lints (`continue-on-error: true`, so lint failures don't block), and runs Jest with coverage. Cypress is not run in CI.
- `kanban-cd.yml` — on pushes to `main`: builds the root Dockerfile and pushes to `ghcr.io/<owner>/kanbanproject-app` tagged `latest` and the commit SHA.
- `codeql.yml` — CodeQL analysis of the Java backend.
- [terraform/](terraform/) — Azure deployment (Container Apps behind a VNet, Postgres Flexible Server, Key Vault, Log Analytics) split into `modules/{vnet,key_vault,postgres,container_app}`. Environments are separated by distinct backend state keys rather than workspaces: `terraform init -reconfigure -backend-config="key=env/dev/terraform.tfstate"`, then `terraform plan -var-file "dev.tfvars"`. See [terraform/README.md](terraform/README.md) for the Azure RBAC prerequisites — it is the authoritative doc for infra work.

### i18n

Nine locales live in [frontend/public/locales/](frontend/public/locales/) (`ar`, `de`, `en`, `es`, `fr`, `it`, `ja`, `pl`, `ru`), loaded at runtime by `i18next-http-backend` with browser language detection. User-facing strings — including every toast raised from `KanbanContext` — go through `t()` with a translation key, so a new message means adding the key to all locale files.
