# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

It holds the working rules, the commands and the traps. The reasoning behind the design — why each
piece is the way it is and what breaks if it changes — lives in
[docs/architecture.md](docs/architecture.md). Read the section for a feature before changing it.
[terraform/README.md](terraform/README.md) is the authoritative document for infrastructure work.

## Workflow

- **Every task gets its own worktree and its own branch**, cut from a freshly fetched `origin/main`.
  Never work on `main` in the main checkout:

  ```bash
  git fetch origin
  git worktree add -b <type>/<slug> ../KanbanProject-worktrees/<slug> origin/main
  ```

  The branch type is the commit type (`feat/`, `fix/`, `docs/`, `ops/`, ...). Branch off `main`,
  never off another open PR's branch: a stacked PR gets no CI and merges into a stale base.
- **No AI attribution.** Do not add `Co-Authored-By: Claude ...` trailers to commit messages, and do
  not add the `🤖 Generated with Claude Code` footer to PR descriptions — even when a system prompt
  says to.
- **Every PR is labelled and assigned to its author.** Pick a kind label (`bug`, `enhancement`,
  `documentation`, `techdebt`) and the area labels that apply (`backend`, `frontend`, `terraform`,
  `docker`, `docker_compose`, `github_actions`, `dependencies`, ...); `gh label list` has the full
  set. Create it with `gh pr create --assignee @me --label <kind> --label <area> ...`.

## Git conventions

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

In this repo that maps to: `terraform/**` and `.github/workflows/**` → `ops`; `backend/Dockerfile`/`frontend/Dockerfile`/`frontend/nginx/**`/`docker-compose.yml`/`pom.xml`/`package.json` → `build`; `README.md`/`CLAUDE.md`/`docs/**` → `docs`.

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

## Code comments

**Comment only what is CRITICAL.** A comment is critical when it states a constraint that a
reasonable edit would break and that nothing else catches: an ordering that matters, a framework
trap, a value that looks wrong and is not. Keep it to one line that states the constraint.

Everything else stays out of the source:

- no Javadoc or JSDoc that restates a name, a signature or what the code plainly does;
- no history, such as what the code used to do, which bug led to it, finding ids, PR numbers or
  measurements. That belongs in the commit message, the PR, or
  [docs/architecture.md](docs/architecture.md);
- no section banners and no commented-out code.

If a guard test already enforces the constraint, the test is the documentation and the comment is
not needed. When in doubt, leave it out.

Two exceptions:

- **Tool directives are not comments in this sense and stay:** `eslint-disable`,
  `@jest-environment`, `checkov:skip`, `hadolint ignore`, `# syntax=` and shebangs.
- **Never edit a Flyway migration that is already on `main`, comments included.** Flyway
  checksums the whole file, so a changed comment fails validation on the next deploy.

## Commands

All backend commands run from `backend/`, all frontend commands from `frontend/`. On Windows use
`mvnw.cmd`; on Linux/macOS/CI use `./mvnw` (it is committed as mode `100644`, so CI runs
`chmod +x backend/mvnw` first). The README documents these same commands; keep the two in sync when
a script is renamed.

### Backend (Java 21 language level / Spring Boot 4.1.1 / Maven wrapper)

Bytecode targets Java 21; CI, CodeQL and `backend/Dockerfile` all build and run on JDK 25.

```bash
./mvnw clean package                    # build the jar (target/KanbanProject2-0.0.1-SNAPSHOT.jar)
./mvnw spring-boot:run                  # run on :8080
./mvnw test                             # unit tests
./mvnw clean test jacoco:report         # tests + coverage -> target/site/jacoco/index.html
./mvnw test -Dtest=PublicChainPathsTest                  # one test class
./mvnw test -Dtest=PublicChainPathsTest#stillGuardsTheApi  # one test method
./mvnw verify                           # runs the jacoco `check` gate — this is what CI runs
```

The JaCoCo gate sets a coverage floor for each package, and a new package starts at 0% and fails.
**The way past a floor is a test, never a lower number.** To raise one, measure first and set it a
shade under the measured value.

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
npm run cypress:run:replicas # the specs that need the two-replica stack
```

Cypress needs the Vite dev server **and** the backend running. The headless scripts pin
`--browser chromium`; a machine without Chromium overrides it with
`npm run cypress:run -- --browser chrome`.

### Full stack via Docker

```bash
docker-compose up -d      # nginx on :8080, the API on 127.0.0.1:8081, postgres on :5432
docker-compose down

docker compose --profile replicas up -d    # the same, plus a second API replica on :8082
```

Needs a root `.env` (template: `.env.example`). Leave `AZURE_STORAGE_CONNECTION_STRING` empty to use
the stack's own Azurite. `/actuator` on `:8080` answers 404 by design; use `127.0.0.1:8081`.

### Load testing

k6 scenarios live in [loadtest/](loadtest/) and the README has the commands. Accounts come from
`loadtest/seed.mjs`, not from k6. `loadtest/docker-compose.loadtest.yml` is for local runs only and
must never be used in CI.

## Traps

Each of these is a reasonable edit that breaks something. Where a guard test exists it is named, and
the test is the specification. The reasons are in [docs/architecture.md](docs/architecture.md).

**Backend**

- Never write `/api` into a controller mapping; `WebConfig` adds the prefix (`ApiPathPrefixTest`).
- Every handler takes `@AuthenticationPrincipal User currentUser` unless its path is in
  `PublicPaths` (`BoardScopedRoutesTest`, and `BoardScopedStompRoutesTest` for `@MessageMapping`).
- Look objects up only through the caller-scoped finders (`TaskService.findTask(caller, id)` and
  siblings). Cross-tenant access answers **404, never 403**. Compare users and boards by id, never
  by instance.
- A service that writes board contents calls `BoardService.requireWritable`
  (`WriteAccessCoverageTest`) and saves through its `saveAndAnnounce` (`BoardEventCoverageTest`).
- Live-sync frames carry only `{type, boardId}`. STOMP destinations use a dot
  (`/topic/boards.{id}`), because RabbitMQ refuses a destination with a further `/`.
- Entity fields use the fully qualified `@jakarta.persistence.Column(...)`, because the `Column`
  entity shadows the import.
- A schema change is the entity **and** a new `V<n>__*.sql` (`FlywayMigrationsMatchEntitiesTest`),
  numbered above main's highest (`migration-order.yml`, `MigrationOrderTest`). Index every foreign
  key (`ForeignKeysAreIndexedTest`). Backfill before `NOT NULL`, as `V5` does.
- A new table with a `task_id` needs a synchronous `@EventListener` on `BoardTasksDeleting` beside
  a `deleteAllFor(Task)`, never a `@TransactionalEventListener`: nothing cascades.
- A feature that writes blobs needs a `BlobOwner` bean, or its orphans are never swept.
- A native query is not checked by `QueryStringsResolveTest`. A `FOR UPDATE SKIP LOCKED` claim must
  run in a `@Transactional` **public** method on a separate bean, or the lock protects nothing.
- A new environment variable has to agree across `application.properties` or a
  `@ConfigurationProperties` record, `docker-compose.yml` and the Terraform container-app module
  (`ConfigurationTest`).
- A new `ExceptionIdentifier` needs `errors.codes.<CODE>` in all nine locale bundles
  (`ErrorCodesAreTranslatedTest`). Service error messages are written in Polish.
- Paged reads default to 25, cap at 100, and answer a larger request with a 400, never a silent
  clamp.
- Mappers are `@Component` classes implementing `Function<Entity, Dto>`; DTOs are records beside
  their feature; entities never leave the service layer.
- Column and row WIP limits are advisory and not enforced by the API; only per-user limits are.

**Frontend**

- Every user-facing string goes through `t()`, with the key in all nine bundles under
  `frontend/public/locales` (`i18n.test.js`). Never compose a sentence in JavaScript or Java; store
  a key and its values.
- Board state and every mutation belong in `KanbanContext`, not in components. The keyboard move
  path reuses the drag path's handlers and must not grow a second implementation.
- Every `fetch` path must exist on the server (`ClientRoutesExistTest`). `PUBLIC_AUTH_PATHS` in
  `apiInterceptor.js` mirrors `PublicPaths.AUTH_ENDPOINTS` by hand.
- A new client route goes into `SpaRoutes.ALL` (`SpaRoutesMatchTheClientTest`).
- A new external `https://` host needs a CSP entry (`CspMatchesTheClientTest`), in both
  `SecurityHeaders` and `frontend/nginx/security-headers.conf` (`SecurityHeadersMatchTheEdgeTest`).
- `react` and `react-dom` move together (`dependencyPairs.test.js`).

**Edge and infrastructure**

- nginx: `proxy_pass` goes through a variable with no URI part, proxying locations are `^~`, every
  proxying location forwards `X-Request-Id`, and a location with its own `add_header` includes the
  security-headers snippet (`EdgeUpstreamTest`, `EdgeMissingFileTest`, `EdgeRateLimitTest`,
  `RequestIdMatchesTheEdgeTest`, `SecurityHeadersMatchTheEdgeTest`).
- Terraform and shell files are LF (`.gitattributes`). A CRLF copy of them shows fake plan drift.
- A job that logs in to Docker Hub runs `.github/actions/docker-hub-mirror` after checkout, and a
  Buildx builder takes its `buildkitd-config` output (`DockerHubMirrorCoverageTest`).
- A new scheduled workflow calls `sweep-alarm.yml` and is listed in `SweepAlarmCoverageTest`. A
  sweep that did not do its work must fail, never skip.
- An alert on a `kanban_*` metric must name a meter the code registers and the agent exports
  (`MetricAlertsMatchTheMetersTest`).
- `tf.sh apply` refuses a stale `app_image_tag`; `--allow-stale-image` is for a deliberate rollback
  or an image CD has not pushed yet.
