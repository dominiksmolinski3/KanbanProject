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

The JaCoCo `check` goal is bound to `verify`, so `package` and `test` skip it — **CI runs `verify`**,
which is the phase the check was bound to all along. It is a real ratchet ([pom.xml](backend/pom.xml)):
every package carries its own LINE floor set just under where it measures, plus a bundle rule and a
`0.15` catch-all that only an unnamed package can land on, so a brand-new package starts at 0% and
fails the build. The way past a floor is a test, never a lower number — when raising one, measure
first (`./mvnw clean test jacoco:report`, then read `target/site/jacoco/index.html`) and set the
floor a shade under, leaving small packages more slack than large ones because at 20 lines a 0.95
floor is one line away from a tripwire.

Test sources sit beside the code they cover, one package per feature. Everything below `layout`,
`file` and `task/history` has a service-level suite; `config`, `config/websocket` and `service` are
what a next round would raise. The suites are all unit tests over mocked collaborators — what that
cannot see is covered instead by the database-free build guards listed under **Architecture**.

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

Requires a root `.env` (template: `.env.example`) supplying `SPRING_DATASOURCE_DB`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET_KEY`, `ACS_EMAIL_CONNECTION_STRING`, `ACS_EMAIL_SENDER_ADDRESS`, `CAPTCHA_SECRET`, `CAPTCHA_ENABLED`, `VITE_RECAPTCHA_SITE_KEY`.

## Architecture

### One deployable, two source trees

This is a monolith, not two services. The Dockerfile builds `frontend/` with Vite, copies `dist/` into `backend/src/main/resources/static/`, then builds the Spring Boot jar — so in production React is served by Spring Boot from the same origin on port 8080, and `api.js` calling relative paths like `/api/columns` just works.

Because the jar serves the bundle, Spring Security has to let the bundle through: `/assets/**` (Vite's hashed JS/CSS) and `/locales/**` (the runtime i18n files) are in `PUBLIC_STATIC_ASSETS` alongside root-level files. Single-segment patterns like `/*.js` do **not** match `/assets/index-<hash>.js` — `*` never crosses a `/` — and getting this wrong serves `index.html` and then 403s the script that boots it. `PublicBundlePathsTest` locks the whole set down; nothing else does, since Jest stubs `fetch` and Cypress runs against the Vite dev server.

In local development the two run separately (`:5173` and `:8080`) and [vite.config.js](frontend/vite.config.js) proxies `/api` (the whole REST surface) and `/ws` (SockJS) to the backend. Because the backend applies the `/api` prefix centrally, a new endpoint needs no proxy change.

**Every REST route is served under `/api`.** [WebConfig](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/WebConfig.java) applies the prefix in one place via `configurePathMatch` + `HandlerTypePredicate.forAnnotation(RestController.class)`, so controllers declare their own mapping (`@RequestMapping("/tasks")`) and are served at `/api/tasks`. **Never write `/api` into a controller mapping** — it would be served at `/api/api/...`; `ApiPathPrefixTest` fails the build if you do. `ChatController` is a plain `@Controller` carrying `@MessageMapping`, so the predicate leaves its STOMP destinations alone.

The prefix exists to keep the API off the paths React Router owns. `App.jsx` serves `/board` and `/users`; before the prefix, `/users` resolved to `UserController` and the page was unreachable on a refresh. Client routes are listed once in [SpaRoutes](backend/src/main/java/pl/myproject/kanbanproject2/config/SpaRoutes.java), which both `WebConfig` (forwards them to `index.html`) and `SecurityConfiguration` (permits them) read. **Adding a `<Route>` to `App.jsx` means adding it to `SpaRoutes.ALL`**, or the deep link 403s.

### Backend layering

Packages are organised **by feature, not by layer**: `board/`, `task/`, `task/subtask/`, `task/history/`, `user/`, `user/auth/`, `layout/column/`, `layout/row/`, `chat/`, `file/`, with cross-cutting code in `config/` and `exception/`. A feature package holds its own entity, controller, service, repository, mapper and DTOs together. (`controller/` still holds `AuthenticationController`, `ChatController` and `FileController`, which have not been moved into their feature packages.)

Within a feature the flow is controller → service → repository, with `mapper` classes converting entities to DTOs. Conventions worth matching:

- Mappers are `@Component` classes implementing `Function<Entity, EntityDTO>`; services call `mapper::apply`. Entities never leave the service layer except where controllers still accept raw entities as request bodies (e.g. `TaskController.createTask`/`patchTask` take a `Task`).
- DTOs are Java `record`s living beside the feature they describe (`task/TaskDto.java`, `layout/column/ColumnDto.java`) — there is no shared `dto/` package.
- Services throw `EntityNotFoundException`, catch it, and rethrow as `ResponseStatusException`; [GlobalExceptionHandler](backend/src/main/java/pl/myproject/kanbanproject2/exception/GlobalExceptionHandler.java) maps validation failures to 400 bodies.
- Error messages in services are written in Polish; UI strings are translated separately through i18n.

### Tenancy: boards with members

`Board` is the unit of access. Every `Column`, `Row` and `Task` carries a non-null `board_id`, and
being on a board's member list is the only thing that grants access to anything on it. Two levels
only: the **owner** may rename, delete and change the membership; a **member** may do anything to the
board's contents.

Three conventions follow from it, and all three are load-bearing:

- **Every controller method takes `@AuthenticationPrincipal User currentUser` and passes it to the
  service.** `BoardScopedRoutesTest` scans every `@RestController` and fails the build for any
  handler that takes no caller and is not on a path in `PublicPaths` — so a new route is either
  public on purpose or it checks who is asking.
- **Services never look an object up without the caller.** `TaskService.findTask(caller, id)`,
  `ColumnService.findColumn(caller, id)` and their siblings are the only lookups; each throws the
  feature's own `*_NOT_FOUND` when the object is on a board the caller cannot see.
- **Cross-tenant access answers 404, never 403.** A 403 would confirm the id is in use, which is
  enough to map somebody else's board by walking ids. 403 (`NOT_BOARD_OWNER`) is reserved for a
  caller who can already see the board and simply does not own it.

`BoardService` is the only place the checks live, and it depends on repositories only — the feature
services depend on it, never the other way round, so a check cannot be short-circuited by a service
that has already run one. `Board.isVisibleTo` compares **ids, not instances**: the caller comes from
the JWT filter and the members from the persistence context, and `User` inherits identity equality.
The same trap produced a real bug on this branch (`/api/users` listed the caller twice), which is why
`peersOf` and `everyone()` key on id.

Listings and creates take an optional `?boardId=`; leaving it out means "the caller's own board",
which is what lets the pre-boards client keep working. Routes that already name an object take the
board from the object. `BoardService.defaultFor` provisions a board (with the default columns from
`V3`) for any account that has none — including the first account to open one on a fresh install,
which **adopts** the ownerless board `V5` created for the seeded columns.

`GET /api/users` lists only accounts the caller shares a board with, not the whole `users` table, and
`File` carries an `owner_id` that `FileService` checks on read and delete. A file with no owner —
anything uploaded before that column existed, other than an avatar, whose owner `V5` recovers from
`users.avatar_id` — belongs to nobody rather than to everybody.

### The board model

`Task` sits at the intersection of a `Column` (workflow stage, horizontal) and an optional `Row` (swimlane, vertical). Both `Column` and `Row` carry `position` and `wipLimit`; `Task` carries `position` within its cell. Tasks also support self-referencing parent/child links, a `SubTask` list, a `Set<String> labels` element collection, many-to-many `users`, and a `deadline`/`expired` pair.

Because the entity is named `Column`, `jakarta.persistence.Column` cannot be imported — entity field annotations are written fully qualified as `@jakarta.persistence.Column(...)`. Keep that pattern when adding fields.

The board is carried on `Task` itself rather than read through its column, because the column is
nullable — a task can be taken off the board — and a task with no column would otherwise be a task
with no owner. `TaskService` refuses any move that would put a task in a column or swimlane on a
different board (`BOARD_MISMATCH`).

**WIP limits are enforced asymmetrically.** Column and row limits are advisory: the backend stores them, and [Board.jsx](frontend/src/components/Board.jsx) only highlights over-limit cells. Per-user limits are the only ones with a server-side check, via `UserService.checkWipStatus`. Don't assume a column limit will be rejected by the API.

`TaskService.reorderTasks` requires every id to name a task in the **same cell** — the same column
*and* the same swimlane, comparing ids so that "no column" is a cell of its own rather than a
wildcard — because a position is an ordinal within one container. Column and row reorders require
one board. Either mistake, and a repeated id, is `400 INVALID_REORDER`.

Task completion has a real server rule: `TaskService.canTaskBeCompleted` refuses to complete a task whose parent is still open, and un-completing a task cascades to dependents.

A `@Scheduled(fixedRate = 1800000)` job in `TaskService` sweeps deadlines every 30 minutes to flag expired tasks, enabled by `@EnableScheduling` on the application class.

### Frontend state

Three React contexts, composed in [App.jsx](frontend/src/App.jsx): `AuthProvider` wraps the router; `KanbanProvider` and `ChatProvider` wrap the protected routes.

[KanbanContext.jsx](frontend/src/context/KanbanContext.jsx) is the single source of board state (columns, rows, tasks, users) and owns every mutation plus all HTML5 drag-and-drop handlers. Its pattern throughout: call the API, optimistically update local state, fire a `react-toastify` notification keyed through `t()`, and often follow with `refreshTasks()`/`refreshBoard()` to resync. Reordering issues **one** call for the whole container — `reorderTasks`, `reorderColumns`,
`reorderRows`, each a `PATCH /api/{tasks,columns,rows}/positions` carrying `{ orderedIds }` and
applied in a single transaction. It used to be one `updateTaskPosition` per item, which stopped
being merely wasteful once the entities gained a `@Version`: a card somebody else moved makes one
of those calls a 409 and leaves the earlier ones applied, so a failed drag left the board half in
the old order. A 409 now surfaces as `ConcurrentModificationError` from `api.js`, which the context
answers with the `notifications.changedBySomeoneElse` toast and a refresh rather than an error —
nothing was applied, so nothing is broken. New board behavior belongs here rather than in
components.

Drag payloads are typed through `dataTransfer` MIME types — `application/task`, `application/column`, `application/row` — and `handleDrop` branches on which type is present, with a `taskId`/`columnId` plain-text fallback.

### Auth

JWT bearer tokens, stateless sessions, **fifteen-minute** access-token expiry
(`security.jwt.expiration-time`). `/api/auth/**` (signup, login, verify, resend, forgot-password,
reset-password, refresh, logout), the health/info actuator endpoints, `/ws/**`, the SPA shell (`/`
plus `SpaRoutes.ALL`) and the static bundle are public; everything else — the whole of `/api/**` —
requires authentication. Signup goes through an emailed verification code before login works, and
login can require a Google reCAPTCHA check. **`POST /api/auth/verify` answers with a session**,
the same `LoginResponse` login returns: the code came from the mailbox and is spent in redeeming
it, so asking for the password on the next screen proves nothing new. It answered `204` before,
and the client — which already read a token out of that response if one was there — had nothing
to act on, so a verified account was left sitting on the verification form.

**A session is a row, not the JWT.** `RefreshToken` (`refresh_tokens`, `V7`) stores a SHA-256
digest of a 256-bit token, never the token, and `RefreshTokenService` is the only thing that reads
it. Login answers with both tokens; `POST /api/auth/refresh` exchanges a refresh token for a new
pair and **spends the one it was given** — presenting an already-rotated token is read as theft and
withdraws every live token the account holds. `POST /api/auth/logout` withdraws one; changing or
resetting a password withdraws them all. Both new routes are public (the caller reaching for
`/refresh` is the one whose access token has just lapsed) and both are on the `CREDENTIALS` rate
limit — `SessionRoutesTest` fails the build if either stops being either. Nothing can retract an
access token already issued, which is why fifteen minutes rather than an hour.

**The refresh token has two deadlines (`V8`).** `expires_at` slides — each rotation issues a
replacement `refresh-expiration-time` (30 days) out, so an account in regular use is never signed
out by it — and `absolute_expires_at` does not: it is stamped at the login that starts the chain
and copied forward unchanged through every rotation, and the effective expiry a check reads is the
earlier of the two. `refresh-absolute-expiration-time` (90 days, and validated at startup to be
≥ the sliding window) is the ceiling the window cannot slide past. It exists for the one theft
revocation cannot catch: a stolen refresh token whose holder rotates it ahead of the real client
is never flagged as reuse, and without a hard ceiling that chain lasts forever. Once the ceiling
passes, `RefreshTokenService.rotate` rejects the chain through the same expiry check that rejects
any lapsed token, and the account signs in again.

**The rate limiter is a burst then a doubling cooldown, not a quota.** `AuthRateLimiter` keys one
escalation per (rule, dimension, key): a key gets its free burst, and after that each attempt sets
the wait for the next one — 15s, 30s, 60s, and so on to a ceiling (5m for `CREDENTIALS`, 15m for
`EMAIL`). It replaced a bucket4j token bucket whose refill made the *first* refusal the worst one:
five signup mails an hour meant the sixth attempt was told to come back in eleven minutes, which
costs an attacker nothing and costs the person who mistyped their address the afternoon. Sustained
abuse converges on the ceiling, a lower long-run rate than the quota allowed, so the change is
kinder at the start and stricter at the end. Three invariants hold it together: escalation is
charged on the way **out**, so a refused attempt changes nothing and hammering neither extends the
wait nor escapes it; a refused attempt still counts as *activity*, so only real quiet (the
per-rule window) forgives a key; and the window must be at least the ceiling, or sitting out the
longest wait would hand the whole burst back. The clock is Caffeine's `Ticker` and readings are
compared as `now - deadline < 0` — `System.nanoTime` has an arbitrary origin and is routinely
negative, so a zero-valued deadline is a point in time, not "no cooldown".

On the client, [apiInterceptor.js](frontend/src/services/apiInterceptor.js) monkey-patches
`window.fetch` at module load to attach `Authorization`, skipping any URL containing `/auth/` (which
still matches the prefixed `/api/auth/...`). Because it wraps the global, tests and any code path
using `fetch` inherit it. An expired access token now renews rather than redirecting, and an
unexpected 401 is retried once; only a refresh the server refuses ends the session and sends the
browser to `/`. [session.js](frontend/src/services/session.js) owns the three `localStorage` keys
and **serialises renewal through one in-flight promise** — rotation means two concurrent refreshes
would present the same token twice, which the server reads as a stolen chain, so a normal board load
firing a dozen requests at once must make exactly one refresh call.

The renewal has to happen **before** the token reaches the server, which is what makes an idle tab
survive: `storeSession` reads `LoginResponse.expiresIn` as the milliseconds it is (it is
`jwtService.getExpirationTime()` unchanged), so `isAccessTokenExpired` trips ~10s early and the
interceptor renews on the next request rather than sending a dead token. Reading it as seconds put
the stored expiry ten days out, so that check never fired and the fifteen-minute token only ever
failed by arriving expired. The reactive path is the backstop for when it still does: a bearer
token the filter cannot parse or verify — expired, tampered, signed with a retired key — is a
`401 INVALID_CREDENTIALS` via `GlobalExceptionHandler.handleInvalidJwt`, not the catch-all 500 it
used to be, so the interceptor's one retry can catch it and `handleGeneric`'s error-level log does
not fire on every lapsed session. Kicking an idle-but-present user to the sign-in screen buys
nothing here — the refresh token is in the same `localStorage` either way — so the client never
does it while a renewal is possible.

CORS allowed origins are hardcoded in two places that must stay in sync: [SecurityConfiguration](backend/src/main/java/pl/myproject/kanbanproject2/config/security/SecurityConfiguration.java) and [WebSocketConfig](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/WebSocketConfig.java). They have already drifted — the WebSocket list additionally allows `http://kanbanproject.pl` and `http://www.kanbanproject.pl`.

### Chat

STOMP over SockJS at `/ws`, simple in-memory broker on `/topic` and `/queue`, app prefix `/app`, user prefix `/user`; `WebSocketAuthInterceptor` authenticates the inbound channel. `ChatContext` uses a reducer (not `useState`) and delegates the connection to [chatApi.js](frontend/src/services/chatApi.js), which points SockJS at `window.location.origin` — correct for the single-origin monolith, so only a chat server on a separate host would need a configured URL rather than the page's.

### Configuration and secrets

`application.properties` resolves everything from environment variables and imports `optional:file:.env[.properties]`, so a `.env` in the backend working directory supplies local values (template: `backend/.env.example`). `KanbanConfig` additionally loads dotenv directly via `io.github.cdimascio:dotenv-java`. `.env` files are gitignored.

**Mail goes out over the Azure Communication Services Email API, not SMTP.** [EmailConfiguration](backend/src/main/java/pl/myproject/kanbanproject2/config/EmailConfiguration.java) builds an `AcsEmailSender` from `app.mail.*` — a connection string and a MailFrom address the linked domain actually has. There is no `spring-boot-starter-mail` and no `jakarta.mail` on the classpath; the `PersistentSmtpMailSender` that used to hold one Gmail connection open, ping it every four minutes and tell a dropped link from a rejected message is gone, and so is every knob that tuned it. The deployment is on Azure, a personal Gmail account is not a sending quota to build on, and an HTTPS request has no session to keep alive.

Three things about it are load-bearing:

- **`EmailService` composes, `EmailSender` transports.** Callers see `EmailService` and a `EmailDeliveryException`; which provider carries the message is behind the interface. That seam is also where a queue would go — an `EmailSender` that writes to Service Bus and returns, with a worker holding the real one, changes nothing above it. Sends are still **synchronous on the request thread**, so a slow provider is still a slow signup; that is the open item, not an oversight.
- **`beginSend` has already posted by the time it returns.** The returned `SyncPoller` is dropped on purpose: `SyncOverAsyncPoller` runs its activation — the POST — inside its own constructor, so the message is with Azure and anything it objected to has already been thrown. Polling further would wait on *delivery*, which no request has any use for. The cost is that a message accepted and bounced later is reported nowhere; a delivery-report subscription on the resource is what would surface it, and there is not one yet. `AcsEmailSenderTest` drives the real SDK over a fake transport and fails if activation ever stops being eager.
- **The Netty transport is excluded in favour of `azure-core-http-jdk-httpclient`.** The SDK finds its HTTP client through a `ServiceLoader` at runtime, so nothing about that choice is visible to the compiler — `AzureTransportTest` asserts the resolved client is the JDK one and that Netty is not on the classpath at all. Versions come from the `azure-sdk-bom`; do not pin the Azure artifacts by hand.

With no connection string the bean is a `DisabledEmailSender`: the app starts and drops messages instead of refusing to boot, which is what lets CI and a fresh clone run without an Azure account. Nothing about a dropped message is logged — subjects carry task titles and bodies carry live verification codes, and the last rewrite of this configuration happened because `mail.debug` had been left on and was writing the SMTP dialogue to the application log. The startup warning naming the missing properties is the only signal, deliberately.

**Terraform has not caught up.** `terraform/` still provisions `SPRING-MAIL-USERNAME` / `SPRING-MAIL-PASSWORD` Key Vault secrets and passes them as container-app environment variables; the app no longer reads either. A deployment needs the Communication Services resource, an email domain linked to it, and `ACS_EMAIL_CONNECTION_STRING` / `ACS_EMAIL_SENDER_ADDRESS` in their place.

**Flyway owns the schema; Hibernate only validates against it** (`spring.jpa.hibernate.ddl-auto=validate`). Migrations live in [backend/src/main/resources/db/migration/](backend/src/main/resources/db/migration/) and run at startup.

`V5__add_boards.sql` is the one to read before adding another: it adds a column, backfills it, and
only then makes it `NOT NULL`, in that order, because any other order fails against a database that
already has rows. It puts everything an existing deployment already had onto one board and makes
every existing account a member of it, which is precisely the arrangement those accounts had before
— one shared board — except that it is now written down and checked.

A schema change is therefore two edits, not one: the entity, **and** a new `V<n>__description.sql`. `ddl-auto=validate` will not add a column for you — it refuses to start without it, which on Container Apps is a revision that never becomes healthy. `FlywayMigrationsMatchEntitiesTest` regenerates the DDL Hibernate would emit and fails the build when an entity has moved on without a migration, so that mismatch is caught at build time rather than at startup.

`V1__baseline_schema.sql` is the schema as `ddl-auto=update` left it, generated from the entity mappings under **Spring Boot's** naming strategies rather than Hibernate's bare defaults — that is the difference between `recipient_id` and `recipientId`, and between the `task` table and `Task`. `spring.flyway.baseline-on-migrate=true` means an environment that already has that schema is marked at V1 without re-running it, while a fresh database runs it like any other migration.

`backend/db.sql` is gone. The default columns it seeded are `V3__seed_default_columns.sql`, so local development gets them by the same route as every other environment — and an init script would have left the volume non-empty, which is exactly the state `baseline-on-migrate` reads as "already migrated".

Captcha is currently frontend-only: the widget renders if `VITE_RECAPTCHA_SITE_KEY` was baked in at Vite build time (it is a Docker `ARG`), and `authService.js` sends the token as `captcha: { token }`, but no backend code reads it — `CAPTCHA_SECRET` / `CAPTCHA_ENABLED` are plumbed through docker-compose and Terraform to a server-side check that does not exist yet, so the token is silently ignored.

### CI/CD and infrastructure

- `kanban-ci.yml` — on PRs and pushes to `main`: backend job runs `mvnw clean test jacoco:report` against a Postgres service container (writing a `.env` from secrets first); frontend job builds, lints (`continue-on-error: true`, so lint failures don't block), and runs Jest with coverage. Cypress is not run in CI.
- `kanban-cd.yml` — on pushes to `main`: builds the root Dockerfile and pushes to `ghcr.io/<owner>/kanbanproject-app` tagged `latest` and the commit SHA.
- `codeql.yml` — CodeQL analysis of the Java backend.
- [terraform/](terraform/) — Azure deployment (Container Apps behind a VNet, Postgres Flexible Server, Key Vault, Log Analytics) split into `modules/{vnet,key_vault,postgres,container_app}`. Environments are separated by distinct backend state keys rather than workspaces: `terraform init -reconfigure -backend-config="key=env/dev/terraform.tfstate"`, then `terraform plan -var-file "dev.tfvars"`. See [terraform/README.md](terraform/README.md) for the Azure RBAC prerequisites — it is the authoritative doc for infra work.

### i18n

Nine locales live in [frontend/public/locales/](frontend/public/locales/) (`ar`, `de`, `en`, `es`, `fr`, `it`, `ja`, `pl`, `ru`), loaded at runtime by `i18next-http-backend` with browser language detection. User-facing strings — including every toast raised from `KanbanContext` — go through `t()` with a translation key, so a new message means adding the key to all locale files.
