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

In this repo that maps to: `terraform/**` and `.github/workflows/**` → `ops`; `backend/Dockerfile`/`frontend/Dockerfile`/`frontend/nginx/**`/`docker-compose.yml`/`pom.xml`/`package.json` → `build`; `README.md`/`CLAUDE.md` → `docs`.

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

All backend commands run from `backend/`, all frontend commands from `frontend/`. On Windows use `mvnw.cmd`; on Linux/macOS/CI use `./mvnw`. The wrapper is committed as mode `100644` and `.gitattributes` only pins `/mvnw` at the repo root (the real one is `backend/mvnw`), so Linux consumers have to fix it up first — CI runs `chmod +x backend/mvnw` and `backend/Dockerfile` runs `sed -i 's/\r$//' mvnw && chmod +x mvnw`.

### Backend (Java 21 language level / Spring Boot 4.1.1 / Maven wrapper)

The `pom.xml` pins `<java.version>21</java.version>`, so the bytecode target stays 21, but CI
(`kanban-ci.yml`) and `backend/Dockerfile` both build and run on **JDK 25** (`eclipse-temurin:25`) — one
toolchain across both, which is what Stage 3 of the audit meant by "pin one JDK". The frontend build
image is Node 26, matching `node-version: 26` in CI.

```bash
./mvnw clean package                    # build the jar (target/KanbanProject2-0.0.1-SNAPSHOT.jar)
./mvnw spring-boot:run                  # run on :8080
./mvnw test                             # unit tests
./mvnw clean test jacoco:report         # tests + coverage -> target/site/jacoco/index.html
./mvnw test -Dtest=PublicChainPathsTest                  # one test class
./mvnw test -Dtest=PublicChainPathsTest#stillGuardsTheApi  # one test method
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
npm run cypress:run:replicas # the specs that need the two-replica stack (see below)
```

Cypress `baseUrl` is `http://localhost:5173`, so the Vite dev server **and** the backend must both be running before E2E tests.

The README documents these same commands; keep the two in sync when a script is renamed.

### Full stack via Docker

```bash
docker-compose up -d      # nginx on :8080, the API on 127.0.0.1:8081, postgres on :5432
docker-compose down

docker compose --profile replicas up -d    # the same, plus a second API replica on :8082
```

The stack is the deployment's shape: a `web` service (nginx, the bundle, the proxy) in front of an
`app` service (the jar). A browser uses `:8080`; `127.0.0.1:8081` is the API container itself, for
poking the routes the edge deliberately does not proxy — `/actuator` on `:8080` answers 404 by
design. This is the only place an nginx misconfiguration gets caught before it reaches Azure, which
is what makes the CI `e2e` job worth materially more than it was.

**`--profile replicas` adds a second API replica** (`app2`, on `127.0.0.1:8082`) built from the
same image and handed the same environment through a YAML anchor — the two cannot drift, which is
the point of a second one. It is behind a profile so `docker compose up -d` is unchanged, and it
exists because one claim in this application is unfalsifiable at a single replica: that a board
event published by the replica handling an API call reaches a subscriber whose WebSocket is held by
a *different* one. At N=1 publisher and subscriber are the same JVM and the frame never leaves it,
so the `enableSimpleBroker` the STOMP relay replaced would pass `live-sync.cy.js` exactly as the
relay does. `cypress/replicas/cross-replica-sync.cy.js` is the spec that needs the profile; see
**Live board sync**. It is also the only local stack that has ever run the outbox claim, the
deadline sweep claim and the Redis escalation with a genuine competitor on the other side.

Requires a root `.env` (template: `.env.example`) supplying `SPRING_DATASOURCE_DB`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET_KEY`, `ACS_EMAIL_CONNECTION_STRING`, `ACS_EMAIL_SENDER_ADDRESS`, `CAPTCHA_SECRET`, `CAPTCHA_ENABLED`, `VITE_RECAPTCHA_SITE_KEY`. `AZURE_STORAGE_CONNECTION_STRING` is optional and meant to stay empty: blank points the app at the stack's own **azurite** service, so attachments work locally with no Azure subscription. A deployed account never uses it — Terraform passes `AZURE_STORAGE_BLOB_ENDPOINT` and `AZURE_STORAGE_IDENTITY_CLIENT_ID` from its own outputs, and with `shared_access_key_enabled = false` there is no key a connection string could even be built from.

## Architecture

### Two containers, one origin

There are two images and one public address. [frontend/Dockerfile](frontend/Dockerfile) builds the
Vite bundle and copies it into `nginx-unprivileged`; [backend/Dockerfile](backend/Dockerfile) builds
the Spring Boot jar. nginx serves the bundle from disk and reverse-proxies `/api`, `/ws` and
`/v3/api-docs` to the API container, so the **browser still sees exactly one origin** — which is the
whole point of proxying rather than publishing an `api.` host alongside. `api.js` calling relative
paths like `/api/columns` just works, `apiInterceptor.js` keeps matching on substrings, both SockJS
clients keep pointing at `window.location.origin`, and `connect-src 'self'` keeps covering the
WebSocket. **No JavaScript changed when the deployment split, and none should have to.**

The edge config is [frontend/nginx/default.conf.template](frontend/nginx/default.conf.template),
rendered by the stock nginx entrypoint's `envsubst` at container start so the upstream is a
deployment fact (`API_UPSTREAM`) rather than something baked into the image. Six things in it are
load-bearing, and each corresponds to something in this application that would otherwise break:

- **`proxy_pass` goes through a variable**, with the resolver read from the container's own
  `/etc/resolv.conf` (`NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1`). A literal target is resolved once at
  worker start and cached forever. The plan for this work wrote `168.63.129.16` down as the
  resolver; that is Azure's platform DNS and is right in a VM, and a Container App actually says
  `nameserver 127.0.0.11` — measured by `az containerapp exec` into `kanban-app-dev`. Docker's
  embedded DNS is the same address, so both environments happen to agree, which is exactly the
  coincidence that would have hidden a wrong constant until the first deploy.
- **No URI part on `proxy_pass`.** With a variable and no URI, nginx passes the request path through
  unchanged; adding even a bare trailing slash makes it rewrite, and every `/api` route 404s.
- **`proxy_ssl_server_name on` and `proxy_ssl_verify on`, because both default to off.** In the
  deployment the upstream is `https://kanban-api-<env>.internal.<env-domain>`, and Container Apps
  routes an internal ingress **by SNI** - so without the first, nginx opens TLS to the ingress IP
  naming nobody, envoy cannot tell which app the connection is for, and it resets the handshake.
  That is a 502 on every API call with a perfectly healthy API container behind it, and it is what
  the first apply of the split produced. The second is the half that would have stayed invisible:
  without it the hop is encrypted and unauthenticated, which is the trade this deployment already
  refused when it chose `sslmode=verify-full` over `require` for Postgres. **Nothing local can
  catch either**, which is the point worth keeping: `docker-compose` sets
  `API_UPSTREAM=http://app:8080`, the scheme is the only difference between the stack every guard
  runs against and the stack that serves users, and over `http` all four directives are inert.
  `EdgeUpstreamTest` is the guard, and it is a text assertion over the template rather than a
  behavioural one because a behavioural test needs a real certificate and this suite has no network.
- **`Host` is `$proxy_host`, not `$host`, and the browser's host moves to `X-Forwarded-Host`.** The
  same shape as the SNI bug, one layer up, and it is what the split broke on second: with SNI sent,
  the handshake succeeded and every proxied call answered **404** — the *ingress's* own "Azure
  Container App - Unavailable" page, not Spring's. A Container Apps ingress routes by `Host`, and
  nginx was forwarding the browser's (`kanban-web-<env>…`) to the API app's ingress, which has no
  such app. Verified against the real origin: a valid SNI with a deliberately wrong `Host` returns
  that page byte for byte. Locally the upstream is a bare container that answers whatever arrives on
  its port, so neither rejection can happen — **the deployment's upstream is a router and the local
  one is not**, which is the general form of both bugs and the reason `EdgeUpstreamTest` exists.
- **`client_max_body_size 12m`.** nginx defaults to 1 MB and an attachment is capped at 10 MB, so
  without it every upload over 1 MB is a 413 generated at the edge — `TaskAttachmentService` never
  runs and nothing reaches the application log.
- **`proxy_buffering off` and `proxy_request_buffering off`.** The attachment design's central claim
  is that nothing on either path holds a file; buffering re-introduces exactly that at the edge, and
  silently defeats the `Range`/`206` resume the client was taught to send.
- **`$proxy_add_x_forwarded_for`, never `$remote_addr`.** The former appends to the header the
  ingress already set. The latter replaces it, collapsing two hops into one and breaking
  `ClientIpResolver` from the other direction.
- **`location /actuator { return 404; }`.** Not tidiness: `location /` ends in
  `try_files $uri /index.html`, so an unproxied path does not 404 — it answers the app shell with a
  **200**, which to anything checking a status code is indistinguishable from actuator published to
  the internet. Measured on the compose stack before that block existed, and the deployed-contract
  sweep now asserts the refusal. Container Apps probes the API container directly, so nothing needs
  it proxied.

Splitting the tier is also what makes the bundle cacheable at last: Spring Security sends `no-store`
on everything it serves, so the hashed assets had never once been cached. `/assets/` is `immutable`
now and `/index.html` is `no-cache`.

**The edge image runs `apk upgrade` on its own base, and that is not defensiveness.** The pinned
`nginx-unprivileged:1.29-alpine` digest is the newest tag Docker Hub publishes, and its package set
is far enough behind Alpine's security branch that Trivy found **35 fixable HIGH findings** in it —
twenty of them `curl`/`libcurl`, in an image whose only HTTP client is a busybox `wget`. That failed
the CD scan gate on the first run after the split and correctly blocked `:latest` on *both* images,
since `promote` moves both or neither. After the upgrade the same scan reports zero. It does mean a
build is no longer a pure function of the pinned digest — what varies is a set of distro package
versions that only ever moves forward, which is the right way round: a base nobody upgrades is
reproducibly vulnerable, and the daily CD sweep rebuilding the tip is what re-runs this. Switching
to `dhi.io/nginx` for consistency with the node stage was the alternative and is declined: it
carries no `/docker-entrypoint.d`, no `envsubst` and no `wget`, so it would mean hand-writing the
templating, the resolver discovery and the healthcheck.

**One identifier per request joins the two log streams, which had nothing in common before.** A
502 recorded at the edge could not be matched to the API line for the same request — and that is
not a hypothetical class of bug here, it is the class the split has already produced twice (the SNI
reset and the wrong `Host`), both diagnosed by hand-building `curl` against the real origin because
the logs could not answer it. nginx mints the id from its own `$request_id`, forwards it on
`X-Request-Id` from every location that proxies, and logs it as `rid=` beside `upstream_status` —
the field that separates "the API answered 500" from "the API never answered".
`RequestIdFilter` reads it into the MDC and echoes it on the response, which the **edge
deliberately does not do**: `add_header` does not inherit into a location that sets one of its own,
so an edge-side echo would mean including the security-headers snippet in every proxying location
and overriding what Spring already sends there.

Four details carry it. An inbound id is **bounded on both sides of the hop** — same length, same
alphabet — because whatever arrives ends up in every log line for that request, and the API
container is reachable without going through nginx (`127.0.0.1:8081` locally, the platform's probes
in the deployment); honouring one at all is deliberate, since it is what lets a load test correlate
its own request with both halves. The filter runs at `HIGHEST_PRECEDENCE`, ahead of the security
chain's `-100`, because a request refused with a 401 is exactly the one somebody asks about. The
MDC entry is removed in a `finally`, since servlet threads are pooled and a leak stamps the next
request with the previous one's id — which reads as true. And the `map` and `log_format` sit at
**http** level rather than inside `server`, which the template can do because the stock entrypoint
renders it into `conf.d`; nginx refuses to start on either directive in a server block.

**Structured logging needed no `logback-spring.xml` and no encoder dependency.** Spring Boot writes
ECS JSON from `logging.structured.format.console` alone and turns every MDC entry into a field, so
`requestId` is queryable next to the edge's `rid=`. It defaults to **off**, because a unit test's
output and `spring-boot:run` are read by a person; `docker-compose` and the container app both set
it to `ecs`, so the two places a machine collects logs are configured alike and the local stack
still exercises the format. `RequestIdMatchesTheEdgeTest` holds all of it together, including that
*every* proxying location forwards the header — a fourth added later without it would be a route
whose application lines carry an id the edge never logged.

Two guards carry the parts no compiler can see. `SecurityHeadersMatchTheEdgeTest` reads
[frontend/nginx/security-headers.conf](frontend/nginx/security-headers.conf) and fails when it and
`SecurityHeaders` disagree — nginx serves `index.html`, so a CSP Spring writes reaches nobody on the
document, and the policy is now a rule in two files, the same trade `DeadLetterAlertTest` makes
against the Terraform. It also pins two nginx traps: that every header is set `always` (otherwise a
404 or a 502 from the edge arrives unprotected), and that **every location adding a header of its own
includes the snippet**, because `add_header` does not inherit into a block that sets one — which
would serve the shell with no policy at all and nothing in the response to say so. The second guard
is `SpaRoutesMatchTheClientTest`, for the reason below.

Because Spring no longer serves files, `PublicPaths.STATIC_ASSETS` is gone with its fifteen
`permitAll` patterns, `WebConfig` registers no view controllers, and
`spring.web.resources.add-mappings=false` switches the resource handler off outright. That is a real
narrowing rather than tidying: `/*.json` sat uncomfortably next to a free-text label segment, and
this class carried a comment saying so. `PublicChainPathsTest` (formerly `PublicBundlePathsTest`)
asserts the absence from both sides, and `JwtAuthenticationFilterSkipTest` asserts that a path that
used to be the bundle now reads a token like any other — so putting any of it back is a deliberate
act rather than a merge artifact.

In local development the two run separately (`:5173` and `:8080`) and [vite.config.js](frontend/vite.config.js) proxies `/api` (the whole REST surface) and `/ws` (SockJS) to the backend. Because the backend applies the `/api` prefix centrally, a new endpoint needs no proxy change.

**Every REST route is served under `/api`.** [WebConfig](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/WebConfig.java) applies the prefix in one place via `configurePathMatch`, so controllers declare their own mapping (`@RequestMapping("/tasks")`) and are served at `/api/tasks`. The predicate is `forAnnotation(RestController.class)` **and** `forBasePackage("pl.myproject.kanbanproject2")`, composed with `Predicate.and` — `HandlerTypePredicate`'s own builder treats its selectors as *alternatives*, so `.annotation(X).basePackage(Y)` means "X **or** Y" and would prefix `ChatController` too. The package half is what keeps a library's controller where its own documentation says it is: springdoc's `OpenApiWebMvcResource` is a `@RestController`, and without it the published contract moves to `/api/v3/api-docs`. **Never write `/api` into a controller mapping** — it would be served at `/api/api/...`; `ApiPathPrefixTest` fails the build if you do. `ChatController` is a plain `@Controller` carrying `@MessageMapping`, so the predicate leaves its STOMP destinations alone.

The prefix exists to keep the API off the paths React Router owns. `App.jsx` serves `/board`, `/users`, `/sessions`, `/activity` and `/flow`; before the prefix, `/users` resolved to `UserController` and the page was unreachable on a refresh.

[SpaRoutes](backend/src/main/java/pl/myproject/kanbanproject2/config/SpaRoutes.java) used to be load-bearing twice — `WebConfig` forwarded each route to `/index.html` and `SecurityConfiguration` permitted each one — and neither is true any more. nginx's `try_files` answers every client route with no list at all, which is why **the routes are deliberately not enumerated in the edge config**: a list in two places is the drift every guard here exists to catch. What survives is the claim, read by `deployed-contract.yml` (does the origin still answer each route with the shell?) and by `SpaRoutesMatchTheClientTest`, which parses `App.jsx`. That test is new with the split and is the reason to keep the class: while Spring served the shell, a route missing from the list was a deep link that 403'd and somebody noticed on the first try. Now it would be a contract sweep that silently checks one route fewer.

### The published contract

`springdoc-openapi` serves an OpenAPI 3 document for the whole of `/api` at **`/v3/api-docs`**, and
that is the only thing it serves: the dependency is `springdoc-openapi-starter-webmvc-api`, not
`-ui`, so no Swagger console is on the classpath to be exposed on a deployed origin — which is also
the first thing `dast.yml`'s ZAP scan would flag. Point Redoc or a local Swagger UI at the JSON.

Two decisions are load-bearing and neither is visible from the route:

- **The document is public**, in `PublicPaths.DOCS_ENDPOINTS`. A contract that needs a token is not
  published — a generator or somebody wiring up a client reads it before they have an account — and
  route names were never a control here, since every route checks its own caller and a board the
  caller cannot see answers 404 whether or not the path was guessable.
- **Which operations need a token is read from `PublicPaths`, not written down.** No controller
  carries a `@SecurityRequirement`; [OpenApiConfiguration](backend/src/main/java/pl/myproject/kanbanproject2/config/OpenApiConfiguration.java)
  contributes an `OpenApiCustomizer` that marks every operation whose path is not public, off the
  same list `SecurityConfiguration` builds the chain from. The spec says what the chain does because
  both read one list — which matters most at `/api/auth/**`, where `devices` is authenticated and
  its seven neighbours are not. Shapes need no such help: they come from the DTO records, so a
  record change is a contract change.

**`ClientRoutesExistTest` is the guard the contract was wanted for.** It resolves every `fetch` URL
in `frontend/src/services/` — through the endpoint constants, the one-line path helpers and the
`onActiveBoard` wrapper — and fails the build when one names a path no `@RestController` maps.
Neither suite can see this on its own: Jest stubs `fetch`, so a frontend test asserts a request was
made to a string and never that anything serves it, and the backend suite asserts routes no client
necessarily calls. It is a **subset** assertion, not an equality — a served route nothing calls is
not a defect. `FileController` used to be the standing example (owned and unused for revisions);
FEAT-09 read that as the finding it was and removed the controller, the entity and the `files`
table together rather than leaving it as an illustration. The handful of calls whose
path the caller supplies (`reorder(endpoint, ...)`, which is one function for three routes) are
named in `CALLER_SUPPLIED_PATHS` with the routes they stand for, so a genuinely new way of building
a URL fails rather than joining an unasserted set.

### Backend layering

Packages are organised **by feature, not by layer**: `board/`, `task/`, `task/subtask/`, `task/history/`, `task/attachment/`, `user/`, `user/auth/`, `user/avatar/`, `layout/column/`, `layout/row/`, `chat/`, with cross-cutting code in `config/`, `exception/`, `mail/` and `storage/`. A feature package holds its own entity, controller, service, repository, mapper and DTOs together. (`controller/` still holds `AuthenticationController` and `ChatController`, which have not been moved into their feature packages; `FileController` was the third and is gone — see **Attachments**.) `file/` is gone too: FEAT-09 pointed avatars at `BlobStore` the same way attachments already used it, and retired the controller, the `File` entity and the `files` table together, since nothing else read from it.

Within a feature the flow is controller → service → repository, with `mapper` classes converting entities to DTOs. Conventions worth matching:

- Mappers are `@Component` classes implementing `Function<Entity, EntityDTO>`; services call `mapper::apply`. Entities never leave the service layer, and controllers accept validated request DTOs rather than raw entities as request bodies (e.g. `TaskController.createTask`/`patchTask` take `CreateTaskRequest`/`PatchTaskRequest`, not a `Task`).
- DTOs are Java `record`s living beside the feature they describe (`task/TaskDto.java`, `layout/column/ColumnDto.java`) — there is no shared `dto/` package.
- Services throw `EntityNotFoundException`, catch it, and rethrow as `ResponseStatusException`; [GlobalExceptionHandler](backend/src/main/java/pl/myproject/kanbanproject2/exception/GlobalExceptionHandler.java) maps validation failures to 400 bodies.
- Error messages in services are written in Polish; UI strings are translated separately through i18n.

### Tenancy: boards with members

`Board` is the unit of access. Every `Column`, `Row` and `Task` carries a non-null `board_id`, and
being on a board's member list is the only thing that grants access to anything on it. The
**owner** may rename, delete and change the membership. A **member** may do anything to the board's
contents. A **viewer** (`board_members.role`, `V21`, FEAT-08) may see all of it and change none of
it.

**Read-only is enforced per mutation, and `WriteAccessCoverageTest` lists the mutations.** Every
write asks `BoardService.requireWritable`. A viewer can already see the board, so the refusal is
`403 VIEWER_READ_ONLY`, the one case the 404-not-403 rule reserves 403 for. The visibility check
the lookups already make is not a write check. That is how the first cut left `SubTaskService` and
`TaskAttachmentService` open: a viewer could tick subtasks and delete files, every existing test
passed, and the guard never noticed because it only reads the files it names. **A new service
that writes board contents belongs in its map.** The client reads `readOnly` from `KanbanContext`
on the board and in the task panel and hides what the server would refuse. That is a courtesy;
the server's check is the rule.

**Membership is offered and accepted, not assigned (`V14`).** `board_invitations` holds one row per
offer, and `POST /api/boards/{id}/invitations` is the only thing that creates one;
`POST /api/invitations/{id}/accept` is the only thing that puts anybody on a member list. That
replaced `POST /api/boards/{id}/members`, which added an account the moment an owner typed its
address **and answered with the board** — so an owner could diff the member list before and after
and learn whether an address had an account here, which is exactly the oracle every unauthenticated
route is written to avoid. Four things about the row are load-bearing:

- **It names an address, not a user id.** That is what lets an invitation be created for somebody
  who has not signed up: the row waits, and `GET /api/invitations` finds it the first time they log
  in. The alternative — creating an unverified account on their behalf, which is what the audit
  originally sketched — would let any account occupy an arbitrary address and lock its real owner
  out of signup. Addresses are stored lower-cased, so the partial unique index in `V14` is a plain
  one.
- **The response is the invitation and never the board**, and it is byte-identical whether or not
  the address has an account. The one place the two cases differ is the mailbox: `registered`
  picks between "sign in and accept" and "create an account with this address", and the language is
  the recipient's when there is an account to read one from and the *inviter's* when there is not.
- **Re-inviting an address that already has a pending invitation sends no second mail.** It answers
  the row that exists. A route that mails on every click is a way to have this application post
  somebody else's mailbox on request; that is not a complete bound (one board per invitation still
  is one mail each) and the honest fix for the rest is a limit on boards, which does not exist.
- **`BoardService.deleteBoard` clears the invitations by hand**, for the same reason it clears the
  history rows: nothing cascades to them, and an invitation whose board is gone renders as a board
  with no name on the invitee's own screen.

Anything that is not a pending invitation the caller may act on — a wrong id, another board's row
under an owner's path, somebody else's row under `/invitations`, one already answered — is one
`404 INVITATION_NOT_FOUND`. `400 ALREADY_BOARD_MEMBER` is the exception and discloses nothing:
only the owner can reach it, and they are looking at the member list on the same screen.

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

`Task` sits at the intersection of a `Column` (workflow stage, horizontal) and an optional `Row` (swimlane, vertical). Both `Column` and `Row` carry `position` and `wipLimit`; `Task` carries `position` within its cell. Tasks also support self-referencing parent/child links, a `SubTask` list, a `Set<String> labels` element collection, many-to-many `users`, a `deadline`/`expired` pair, and **attachments** — files that live in Azure Blob Storage with a `task_attachments` row naming them (see **Attachments**).

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

**Search is the one route that is paginated, and the board listing deliberately is not.**
`GET /api/tasks/search` takes the same optional `?boardId=` every listing does, plus free text
(`q`, over title and description), repeatable `label` and `assignee` facets, `completed`, and a
`deadlineFrom`/`deadlineTo` window. Facets are **AND across kinds, OR within one** — two labels and
one assignee finds tasks carrying *either* label *and* having that person on them, which is how a
row of filter chips reads. Paging is `page`/`size`, default 25, **maximum 100, and asking for more
is a `400 INVALID_SEARCH` rather than a silent 100** — a caller handed fewer rows than it asked for
cannot tell that from a short last page, and will page past rows it never saw. That ceiling is
PERF-02's "decide what large enough means", answered where the unbounded thing actually is: a board
renders every card it has and is bounded by what a team will put on one, while a search returns
whatever the query selects and an empty query selects the whole board.

Three details in the query are load-bearing and none of them is visible from the route. It is
**two queries and a count**: `findMatchingIds` pages the ids in SQL, `findByIdIn` then fetches that
page with the association graph, and the service **puts the rows back into the id order** — a
second lookup makes no promise about ordering, and getting it wrong shuffles results between pages.
An **unused collection facet is switched off by a boolean parameter** and its list bound to a
placeholder, because a bare `IN` against an empty list is not a clause that is skipped, it is a
clause that matches nothing. And the free-text pattern is built with `ESCAPE '!'` and the wildcards
a person typed are escaped rather than honoured — without that, searching for `100%` matches every
task on the board, which reads as a search that has quietly stopped working. Ordering is by id
because it is total: any order with ties makes paging skip and repeat rows, silently, and only on
boards big enough to page.

On the client, `TaskSearch.jsx` sits in the board toolbar and **asks the server rather than
filtering the board already in memory** — one predicate, on the side that owns the data, instead of
a second copy in JavaScript that can drift from it. Requests are debounced to one per pause in
typing and guarded by a sequence number, because a slow answer to `de` landing after a fast answer
to `deploy` leaves the list showing results for a query nobody can see. Changing any filter resets
to page 0. Results are **a list, not a filtered board**: hiding cards would answer "which tasks
match" by destroying the layout that says where they are, so each result names its cell and
"show on board" scrolls to the real card and flashes it.

Task completion has a real server rule: `TaskService.canTaskBeCompleted` refuses to complete a task whose parent is still open, and un-completing a task cascades to dependents.

**What happened on a board is a second table, not a query over the first (`V15`).** `task_activity`
records creation, moves, assignment, completion and deletion with an actor; `GET /api/activity`
serves it, newest first, paged. It does **not** replace `task_column_history`, and the reason is
worth knowing before merging them: that table is an *interval* series — one row per arrival, folded
by the task panel into time-per-column — and it has never recorded **who**. An activity feed with no
actor is not an activity feed, which is why this is a table rather than a join. Both records of a
move are written in the same three lines of `TaskService.moveToColumn`, which is the only thing
keeping them from drifting.

Four details carry it:

- **`task_id` is nullable and the title is a copy on the row.** The entry saying a task was deleted
  is the one entry that has to outlive its subject, so `TaskService.deleteTask` writes the entry,
  then calls `TaskActivityRecorder.detachFrom` before deleting — nothing cascades, deliberately, for
  the same reason attachment blobs are removed by a service call rather than a foreign key.
  `actor_id`/`actor_name` are the same pattern applied to accounts, and `task_column_history.column_name`
  was the precedent for both: an event log records what was true when it happened. `V17` gave
  `task_column_history.column_id` the same treatment from the other side: it was `not null` with no
  cascade, so deleting a column that had *ever* held a task — not just one holding a task right now —
  failed on that foreign key. `ColumnService.deleteColumn` now detaches those rows (`column_id` set
  to `null`) rather than deleting them, leaving the task and the rest of its history unaffected.
- **The recorder never throws.** A feed entry is a side effect of somebody else's operation, and a
  task with no board records nothing rather than failing the edit that produced it.
- **Nothing stored is a sentence.** The row holds a type name and a detail (the column, or the
  person); `ActivityFeed.jsx` turns it into wording through `t()`. A feed composed in Java would be
  a screen the other eight languages cannot translate.
- **It is paged, and the board listing still is not.** Same numbers and the same refusal as the
  search route — 25 default, 100 maximum, `400 INVALID_ACTIVITY_REQUEST` rather than a silent clamp.
  A board is bounded by what a team will put on one; a feed only grows.

`/activity` is a client route, so it is in `SpaRoutes.ALL` — see the SPA-routing note above.

**Flow metrics are read from `task_column_history`, not stored anywhere (FEAT-07).**
`GET /api/flow` answers a board's cumulative flow diagram, the cycle times of the cards that
finished in a window, and daily throughput. It needs no new table and no new write path. The
interval series has been written on every move since the start and, until this, only the task
panel read it, one card at a time. `FlowMetricsService` does the fold, and five rules decide its
numbers:

- **A window, not paging.** The answer is an aggregate. It defaults to the last 30 days and refuses
  more than 180 with `400 INVALID_FLOW_REQUEST`, the same refusal the other bounded reads make.
- **"Done" is a column, and arriving at or past it counts.** A card that skips Done for Closed
  has still finished. The start works the same way. Positions are today's, because a history
  row points at a column and a column has only its current position. The defaults are the
  board's last column and arrival on the board, which makes the default a lead time; the screen
  lets people choose both.
- **The first finish is the finish.** A card bounced between Review and Done counts once, or
  one card would read as throughput.
- **What the history cannot say is not guessed.** A deleted card takes its history with it. A
  card taken off the board has an open last interval with no recorded end, so it is in no column
  from then on. A detached row (a deleted column, `V17`) is still an interval boundary but draws
  no band.
- **Days are the server's calendar days,** in the same zone `LocalDateTime.now()` writes the rows
  in. A move at 00:00 belongs to the new day. Percentiles are nearest-rank, so every reported
  number is one a card actually took.

`/flow` is a client route, so it is in `SpaRoutes.ALL` too. The screen draws the diagram, a
cycle-time scatter with median and 85th-percentile lines, and throughput bars, in hand-written SVG
with no chart dependency. The eight band colours are a validated categorical palette, and columns
past eight fold into one "earlier columns" band. Every chart has a table view, because three of
the light-mode colours sit below 3:1 against the chart surface.

The same branch fixed `TaskColumnHistoryMapper`. It dereferenced `history.getColumn()`, which
`V17` made nullable, so any task that had left a column that was later deleted answered `500` on
its whole history panel.

A `@Scheduled(fixedRate = 1800000)` job in `TaskService` sweeps deadlines every 30 minutes to flag expired tasks, enabled by `@EnableScheduling` on the application class — the same scheduler `OutboxRelay` runs its minute-by-minute mail pass on.

**The sweep claims the rows it is about to change**, `FOR UPDATE SKIP LOCKED`, for the same reason
the outbox relay does. Two schedulers sweeping at once is not a benign duplicate: both write the
flag, both record the expiry, and both ask `DeadlineNotifier` to mail every assignee, so an overdue
task arrives twice in somebody's mailbox and nothing can recall it. Three things carry it:

- **The claim asks for the rows that change, not for every task with a deadline.**
  `claimTasksCrossingDeadline` selects the ids whose `expired` disagrees with `deadline < now`, and
  the sweep then loads those through `findByIdIn` — the same two-query shape the search uses, and
  the same entity graph. That is what makes locking affordable: the rows held are exactly the rows
  the next statement writes, which any update would have locked anyway. It also stops a half-hourly
  read of every deadline in the deployment to answer a question that is almost always "none".
- **The class-level `@Transactional` on `TaskService` is what holds the claim**, and that is the
  whole reason this needs no lock table, no advisory lock and no new dependency — row locks last as
  long as the transaction, and the sweep is one. Remove it and the lock releases at the end of its
  own statement: identical to read, and protecting nothing. `DeadlineSweepClaimTest` pins it, and
  pins the `SKIP LOCKED` clause with it, because a native query is invisible to
  `QueryStringsResolveTest` — that test compiles the hand-written HQL and skips native queries, so
  nothing but a database checks the string.
- **`COALESCE(expired, FALSE)`**, because the column is nullable and the entity's field is a
  primitive defaulting to `false`. The two have to agree on what a null means, or the sweep selects
  rows it then declines to change, every half hour, forever.

`SKIP LOCKED` rather than a plain `FOR UPDATE` because a second sweep should take the rows the
first is not holding rather than queue behind it for the length of a mail run — with nothing left to
take, it does nothing and returns, which is the correct amount of work for it to do.

### Attachments

A task carries files, and **the bytes are not in the database and never pass through the
application on the way out**. `task/attachment/` holds the feature; `storage/` holds the seam it
goes through — `BlobStore`, with `AzureBlobStore` and a `DisabledBlobStore`, exactly the shape
`EmailSender` has and for the same reason.

The `files` table this replaced was the counter-example rather than the model, and is gone now
(`V20`, see **Avatars**) — it stored an upload as a `@Lob` in Postgres, which put every attachment
into the database's storage, its backups, its point-in-time window and its restore time, for data
no query ever looked inside. At the 10 MB per file this allows and 32 GB of provisioned storage, a
team attaching a mock a day fills the server in a year. `V12` stores metadata only: an opaque
`blob_name`, the name a person typed, the type, the size, who uploaded it and when.

Four decisions carry the feature:

- **The storage account answers nobody but the application.** `public_network_access_enabled =
  false`, a `network_rules` default of `Deny`, and a private endpoint in `snet-storage` that the
  `privatelink.blob.core.windows.net` zone resolves to. That is the constraint the rest of the
  design follows from, and it was a deliberate reversal: the first cut handed the browser a
  five-minute SAS and let it fetch from Azure directly, which is cheaper and requires the account
  to answer every address a browser might arrive from. An account holding every file on every board
  should not be reachable from there, so the bytes came back onto the request path and the SAS
  machinery — delegation keys, signed response headers, the public-endpoint rewrite — was deleted
  rather than kept unused.
- **Nothing on either path holds a file.** Upload hands `MultipartFile.getInputStream()` straight to
  the store; download returns the store's own stream as an `InputStreamResource`, which Spring
  copies through a buffer and closes. Nothing calls `getBytes()`. On a 1 GiB container that is the
  difference between a buffer per transfer and 10 MB per concurrent one, and it is the only reason
  proxying the bytes is affordable at all. `Content-Length` comes from `size_bytes` on the row
  rather than from the stream, which is why that column is stored.
- **The concurrent-transfer limit is fleet-wide, not per JVM.** `TaskAttachmentService` bounds
  uploads and downloads with a `Semaphore`, and used to size it from `app.storage.max-concurrent-transfers`
  alone — correct at one API replica, but at `api_max_replicas` above 1 the true ceiling was that
  number times the replica count rather than the configured one. It is now divided by
  `app.storage.replica-count-hint` (`ATTACHMENT_REPLICA_COUNT_HINT`), floored at one permit;
  Terraform sets the hint to the `api_app` module's own `max_replicas`, so the two move together
  automatically. That undercounts capacity while the deployment is scaled below its ceiling, which
  is the safe direction to be wrong in.
- **`Content-Disposition: attachment`, never `inline`.** The bytes are now served from the app's own
  origin, so an HTML or SVG upload rendered instead of downloaded would be same-origin with the
  board and every token in it. Forcing the download is what makes it safe to echo back whatever
  type was uploaded. The name comes from Postgres — the blob is `tasks/<taskId>/<uuid>`, no
  extension, nothing a person typed, so the storage account never carries a chosen name.
- **A download can be resumed, and the resume goes all the way to Azure.** The route answers
  `Accept-Ranges: bytes` on every response, `206` with a `Content-Range` for a satisfiable `Range`,
  and `416` with `bytes */<total>` for one that names bytes the attachment does not have — the
  length being the whole point of that refusal, since it is the fact the caller was wrong about.
  Two decisions are load-bearing. The range is asked of `BlobStore.read(name, offset, length)`
  rather than served by skipping a full stream: a `ResourceRegion` over the whole blob answers the
  same `206` and still pulls every earlier byte across the private endpoint, so a resume at 90%
  would cost the same egress as starting over. And **the range is resolved in the service, not the
  controller**, because a suffix range (`bytes=-500`) cannot become an offset without the size, and
  the row holding it is the one the service has just read; the transfer permit is taken *after* that
  check, so a request that was never going to be served costs no slot. A `Range` that will not
  parse, and a request for several ranges at once, are both ignored in favour of the whole file —
  spec-legal, and the alternative to the second is a `multipart/byteranges` body nothing here asks
  for. The `416` is answered by a controller-local `@ExceptionHandler` because
  `GlobalExceptionHandler` has no per-exception header plumbing, which is the same trade the
  `Retry-After` on `ATTACHMENT_TRANSFER_BUSY` was declined for.
- **The client is the only thing that ever sends a `Range`, so it had to be taught to.** The
  download goes through `fetch` rather than an `<a href>` because the route is authenticated — which
  also means the browser's own resume machinery never applies, and a `fetch()` that dies at 90% is a
  rejected promise whose bytes are gone. So `downloadTaskAttachment` reads the body through
  `response.body.getReader()`, keeps what arrived, and re-asks with `Range: bytes=<received>-`, up
  to three times. A server that ignores the `Range` and answers `200` makes it drop what it held
  rather than prepend it; an HTTP error is fatal on the first answer, because the next one would say
  the same thing. Without this half the server's range support would be a header nobody sends.
- **There is no account key.** `shared_access_key_enabled = false`; the container app authenticates
  as its managed identity. There is no storage secret in Key Vault, in the container template, or in
  Terraform state. A connection string is accepted too, and is only ever local development against
  Azurite, whose key is a published constant.
- **A blob and a row are two systems, and the order chooses which failure is possible.** Upload
  writes the blob first and the row second, removing the blob if the transaction does not commit;
  delete removes the row and the blob *after* the commit. Both orders leave the same failure
  available — an orphaned blob, invisible and costing a fraction of a cent — and rule out the other
  one, a row whose bytes are gone, which is an attachment that fails every time somebody clicks it.
  `TaskService.deleteTask` calls `TaskAttachmentService.deleteAllFor` rather than relying on a
  cascade, because a foreign-key cascade takes the rows and leaves every blob behind with nothing
  left that knows its name.

Scoping is the task's, entirely: an attachment has no board of its own, which is why the routes are
nested under `/api/tasks/{taskId}/attachments` and the service checks the task before it looks at
anything else. An attachment id from another board presented under a task the caller *does* own is a
404, or the task in the path would be decoration.

With no storage configured the bean is a `DisabledBlobStore`: the app starts, `StorageHealthIndicator`
reports `attachments` as `OUT_OF_SERVICE` on `/actuator/health`, and an upload is a
`503 ATTACHMENT_STORAGE_UNAVAILABLE`. That is the opposite of what `DisabledEmailSender` does, on
purpose — a dropped mail has nobody standing in front of it and there is nothing useful to say,
while an upload has somebody watching a progress bar. `docker-compose` runs an **Azurite** service so
the local stack has working attachments without an Azure subscription.

**Nothing joins the two stores but a string.** `task_attachments.blob_name` is written by the
application; there is no foreign key, no shared transaction, and no network path between Postgres
and Blob Storage at all — Postgres is VNet-only and the blob service is a separate endpoint. Two
consequences are load-bearing. The write ordering above is the only thing keeping a row from
outliving its bytes. And the two recovery windows have to be the same length: `retention_days` on
the storage module comes from `postgres_backup_retention_days` rather than a number of its own,
because a database restored further back than blob soft-delete reaches comes up holding rows whose
blobs were already purged. `attachment_retention_days` unties them deliberately.

If attachment traffic ever outgrows one container, the way out is Front Door with a private origin —
`terraform/front-door-private-origin.md` has the design and the traps, and the first of them is that
there are cheaper things to try first. Reopening the account is not on that list.

Terraform provisions the account and the role assignment but **not the container** — the app creates
its own on first start, because creating one is a data-plane call and keeping Terraform off the data
plane is what lets the account refuse shared-key access. See `terraform/README.md`, *Attachment
storage*.

### Avatars

FEAT-09 pointed avatars at `BlobStore` too — the seam, the quota knobs and the private endpoint
were already built for task attachments, and an avatar was the other place a `@Lob` was still
carrying bytes Postgres never queried into. `user/avatar/` holds the feature: `AvatarService`
(upload, `content`, `delete`) and `UserAvatarController`, at the same route the account-owned
`/users/{id}/avatar` always used — the route did not move, only what backs it. The old `File`
entity, `FileRepository`, `FileService` and `FileController` (`/api/files`, declared in the
client's endpoint list and called by nothing — see `ClientRoutesExistTest`) went with it, and `V20`
drops the `files` table outright rather than leaving it as a second, now-orphaned upload path.

A user has at most one avatar, so this is four nullable columns directly on `users`
(`avatar_blob_name`, `avatar_content_type`, `avatar_size_bytes`, `avatar_uploaded_at`) rather than a
second table with a foreign key back — task attachments need that shape because a task holds many,
a user holds at most one. `V20` adds the columns, drops `avatar_id` and its FK, and drops `files`;
it does **not** attempt to backfill existing avatar bytes into a blob, because this repository has
one deployed environment (dev, disposable for schema purposes — see **CI/CD and infrastructure**)
and no running application to do that copy from inside a migration. An account with an avatar set
before this shipped needs a re-upload; the migration's own comment says so.

Three decisions depart from `TaskAttachmentService`'s shape, each for a stated reason rather than by
accident:

- **The upload is read into memory once, rather than streamed straight through.** An attachment
  streams because it can be ten megabytes with many in flight; an avatar is capped at one megabyte
  and the bytes are needed twice regardless — once to confirm the declared `Content-Type` is not a
  lie and once to hand to the store. `AvatarService` keeps the magic-byte sniff the avatar upload
  path already had before this move (PNG/JPEG/GIF/WebP signatures checked against the declared
  type, both attacker-controlled halves of the same check), on the same reasoning
  `TaskAttachmentService`'s doc states for why the declared type alone is not enough: a client can
  lie about `Content-Type`, so the bytes decide.
- **`Content-Disposition: inline`, never `attachment`.** A task attachment forces a download because
  it can be any file an uploader chose, including HTML or SVG, and rendering one on this origin
  would be same-origin with the board and every token in it. An avatar cannot be either: the
  allow-list and the magic-byte check both refuse anything outside PNG/JPEG/WebP/GIF before a byte
  reaches storage, so serving it inline is safe by construction rather than by trust in what the
  uploader claimed — which is what lets it render as an `<img>` on cards and headers at all.
  `X-Content-Type-Options: nosniff` stays on regardless.
- **Its own transfer-permit `Semaphore`, not a shared one.** Sized the same way — dividing
  `app.storage.max-concurrent-transfers` by `app.storage.replica-count-hint` — but kept separate so
  a burst of avatar uploads cannot starve a task attachment transfer or the other way round.

Reading goes through `UserService.requireVisibleUser` before `AvatarService.content` is ever called,
the same peer-visibility rule `GET /api/users` is built from — an avatar is readable by anyone who
shares a board with the account, not only by the account itself, since it renders on their cards too.
Upload and delete are self-only, checked the same way every other account-mutating route in
`UserController` is. `ATTACHMENT_STORAGE_UNAVAILABLE` and `ATTACHMENT_TRANSFER_BUSY` are reused
rather than duplicated under an avatar-specific name, since both describe the same failure either
way: no storage account configured, or the transfer cap is exhausted.

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

**Every drag has a keyboard equivalent, and it is not a second implementation.** HTML5 drag-and-drop
cannot be driven from a keyboard at all — there is no key that begins a drag — so a card that could
only be dragged could not be moved by anyone without a pointer, which on a Kanban board is the
gesture the board exists for. [keyboardMove.js](frontend/src/context/keyboardMove.js) holds the
state machine and `KanbanContext` exposes it as `keyboardMove`, built on the same `handleMoveTask`
`handleDrop` calls — so the toast, the resync and the activity entry cannot drift from the dragged
path. Space picks a card up, the arrows choose a cell, Space or Enter drops it, Escape puts it back
(the ARIA authoring-practice set, not one invented here).

Three details carry it:

- **Nothing reaches the server until the drop.** The pending target lives in the hook; committing on
  each arrow press would move a card four times to cross four columns, with four toasts, four
  refreshes and four feed rows. A drop onto the cell the card came from is not a move at all.
- **The card does not leave its cell while it is held**, so the focused element never unmounts and
  there is nothing to restore focus to after a cancel. The cost is that "which card am I holding"
  and "where would it land" have to be drawn (`.keyboard-held`, `.keyboard-move-target`) and spoken
  (a `role="status"` live region on the board) rather than being visible from the card's position.
- **The announcement is a key and its values, never a sentence** — the activity feed's rule, for the
  same reason: a sentence composed in JavaScript is one the other eight bundles cannot translate.
  `Board.jsx` renders it through `t()`.

Drag payloads are typed through `dataTransfer` MIME types — `application/task`, `application/column`, `application/row` — and `handleDrop` branches on which type is present, with a `taskId`/`columnId` plain-text fallback.

### Auth

JWT bearer tokens, stateless sessions, **fifteen-minute** access-token expiry
(`security.jwt.expiration-time`). `/api/auth/**` (signup, login, verify, resend, forgot-password,
reset-password, refresh, logout), the health/info actuator endpoints, `/ws/**`, the published
contract and the delivery-report webhook are public; everything else — the whole of `/api/**` —
requires authentication. The shell and the bundle are no longer on that list because they are no
longer served here at all; see **Two containers, one origin**. Signup goes through an emailed verification code before login works, and
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

**A session is also something its owner can see and end (`V9`).** `GET /api/auth/devices` lists
every live token the caller holds and `DELETE /api/auth/devices/{id}` withdraws one — the case
between logout (the session you are holding) and a password change (all of them), which is a device
you no longer have. Both are **authenticated**, the only routes under `/auth` that are, and
`SessionRoutesTest` fails the build if either appears in `PublicPaths`; the rate limiter covers the
unauthenticated routes, so neither is on it. A row that is not the caller's live session — unknown
id, somebody else's, already revoked, expired — is one `404 SESSION_NOT_FOUND`, because ids here are
sequential. `V9` adds `ip_address`, `user_agent` (stamped on every issue, so they say where the
session is *now*) and `chain_started_at` (stamped at login and carried forward like
`absolute_expires_at`, because rotation rewrites `issued_at` and "signed in since" is the thing a
person scanning the list is checking). **Which row is "this device" is answered on the client**:
`LoginResponse` carries a `sessionId`, refreshed on every rotation, and the list is matched against
it — marking it server-side would mean the access token carrying its chain, a filter change and a
lookup on every request, for a fact the client was already handed.

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
longest wait would hand the whole burst back.

**The escalation lives in Redis, not process memory (phase 3 of the container split).** Each
(rule, dimension, key) triple is one Redis hash, read, scored and rewritten by
`redis/auth-rate-limit.lua` in a single `EVAL` — the same reason the outbox claim and the deadline
sweep are native SQL rather than JPQL: the atomic unit has to live where the shared state lives, and
Redis running a script to completion before serving anything else on the keyspace is what makes two
replicas hitting the same key at once score it exactly once between them, the way two Caffeine
caches on two pods never could. "Now" is supplied by the caller as an epoch-millisecond `long` —
`AuthRateLimiter` reads `java.time.Clock`, not Redis's own clock — which is what lets a test drive
the escalation without waiting a cooldown out and keeps every replica scoring the same key against
the same reading rather than against its own uptime. `RedisEscalationStore` **fails the attempt
open** on anything Redis-shaped going wrong — an unreachable connection, a script the response
shape does not match — because the limiter is defence in depth, not the control that stops a stolen
password working, and losing the escalation for the length of an outage is a smaller cost than an
outage that also locks every caller out of authentication. `security.rate-limit.redis-host`
defaults to `localhost` (docker-compose and CI both run a plain `redis:7-alpine`); the deployment
points it at an **Azure Managed Redis** instance — Redis Enterprise underneath, and the resource
classic Cache for Redis is retiring in favour of, which this deployment learned the hard way: the
first apply against dev of the classic `azurerm_redis_cache` was refused outright with
`Azure Cache for Redis is retiring, create Azure Managed Redis instance instead` on a subscription
that had never created either kind before. It sits behind the private endpoint the storage account
and Key Vault already have, authenticated with an access key from `REDIS-ACCESS-KEY`, on the
smallest SKU (`Balanced_B0`) — deliberate, the same way Basic would have been on a classic cache,
since what it holds is exactly the state the store already fails open on losing.

On the client, [apiInterceptor.js](frontend/src/services/apiInterceptor.js) monkey-patches
`window.fetch` at module load to attach `Authorization`, skipping the URLs that name an
unauthenticated auth route. That skip used to be any URL containing `/auth/`, on the assumption that
everything under the prefix is pre-authentication; `/api/auth/devices` is not, and a blanket skip
sent it out bare. `PUBLIC_AUTH_PATHS` is the client's copy of `PublicPaths.AUTH_ENDPOINTS` and has
to be kept in step with it by hand — the cost of forgetting is a 401 on one route, not a token
attached to a public one. Because it wraps the global, tests and any code path
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

CORS allowed origins live in one place — [AllowedOriginsProperties](backend/src/main/java/pl/myproject/kanbanproject2/config/AllowedOriginsProperties.java), a `@ConfigurationProperties` record bound from `security.cors.allowed-origins` (`SECURITY_CORS_ALLOWED_ORIGINS`, comma-separated), which both [SecurityConfiguration](backend/src/main/java/pl/myproject/kanbanproject2/config/security/SecurityConfiguration.java) and [WebSocketConfig](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/WebSocketConfig.java) read. The two used to hold a copy each and had drifted — the WebSocket list additionally allowed the `http://` variants of `kanbanproject.pl` — which is why the list is single-sourced now, with `AllowedOriginsTest` as the guard and the stricter (HTTPS-only) set kept as the default. Terraform passes `SECURITY_CORS_ALLOWED_ORIGINS` to the container app, so a new deployment origin is a tfvars change rather than a rebuild.

**The browser is told what this application may load, and the policy lives in
[SecurityHeaders](backend/src/main/java/pl/myproject/kanbanproject2/config/security/SecurityHeaders.java).**
Spring Security already sent `nosniff`, `X-Frame-Options: DENY` and a no-store `Cache-Control` by
default; what was missing until now was a `Content-Security-Policy`, which matters here more than
on most applications because one origin serves the bundle, the API and every uploaded attachment —
everything an injected script could reach is same-origin with the token that reaches it. It was
found by reading the OWASP ZAP baseline report `dast.yml` had been filing as a GitHub issue on a
schedule and nobody had opened.

Four things are worth knowing before editing the policy:

- **`script-src` has no `'unsafe-inline'`, and that is the half worth anything.** `index.html`
  carries no inline script and Vite emits the bundle as a hashed module, so `'self'` is enough. The
  two Google hosts are reCAPTCHA — `recaptchaLoader` injects `www.google.com/recaptcha/api.js`,
  which pulls its implementation from `www.gstatic.com`, a host no file in this repository names.
- **`style-src` keeps `'unsafe-inline'`, deliberately.** Removing it means nonces, which means a
  server-rendered shell this application does not have; Spring serves Vite's `index.html` as a
  static file. Injected CSS can restyle a page, not read a token.
- **`Strict-Transport-Security` is written unconditionally, and that is not sloppiness.** Spring
  Security sends it by default and had never sent it once: its default writer is gated on
  `request.isSecure()`, and TLS terminates at the Container Apps ingress, which is declared
  `transport = "http"` — so every request this application has ever served arrived as plain HTTP
  and the default fired on nothing. A default that fires on nothing looks exactly like a default
  that works, which is why it was found by a scanner asking the real hostname rather than by any
  suite here. `SecurityConfiguration` overrides the matcher with `AnyRequestMatcher.INSTANCE`; a
  user agent must ignore an HSTS header received over plain HTTP and the ingress redirects, so
  there is no case where writing it is wrong. **`server.forward-headers-strategy` is the other fix
  and is declined**: it would rewrite `getRemoteAddr()` from `X-Forwarded-For` for the whole
  application, which is exactly the decision `security.rate-limit.trusted-proxy-count` exists to
  make deliberately — `ClientIpResolver` reads that header itself and a count of `0` means *ignore
  it*. `preload` stays off: it is a one-way door and this origin is a subdomain of
  `azurecontainerapps.io`, which this deployment does not own.
- **`Cross-Origin-Embedder-Policy` is declined rather than forgotten**, and `SecurityHeadersTest`
  asserts its absence so that adding it is a deliberate act. `require-corp` buys cross-origin
  isolation — worth having for `SharedArrayBuffer` or high-resolution timers, neither of which this
  application uses — and it would break the reCAPTCHA frame, which carries no CORP header of its own.
- **Two guards, because a CSP does not go wrong on the day it is written.** It goes wrong when
  somebody adds a font. `CspMatchesTheClientTest` reads `frontend/src` and fails when the client
  names an `https://` host the policy does not allow — the same shape as
  `SupportedLocalesMatchClientTest`. `cypress/e2e/security/csp.cy.js` listens for
  `securitypolicyviolation` while a real browser loads the sign-in screen and the board, **with a
  control test that provokes a real violation**, because a listener that cannot fire is otherwise
  indistinguishable from a page that causes none.

One thing about running the e2e suite under it: **Cypress strips six CSP directives from the
responses it proxies** — `script-src`, `script-src-elem`, `default-src`, `form-action`, `child-src`
and `frame-src` — so a suite that passes "with a CSP" is, by default, a suite that ran with the
directives that matter removed. `experimentalCspAllowList` in `cypress.config.js` names all six so
the application under test runs under the real policy. `frame-ancestors` is stripped regardless and
cannot be named there, because the application under test genuinely does run in an iframe; that one
is checked by reading the header instead.

### Chat

STOMP over SockJS at `/ws`, relayed to a real broker (RabbitMQ, via its STOMP plugin — see
[StompRelayProperties](backend/src/main/java/pl/myproject/kanbanproject2/config/websocket/StompRelayProperties.java)
and `terraform/modules/broker`) on `/topic` and `/queue`, app prefix `/app`, user prefix `/user`;
`WebSocketAuthInterceptor` authenticates the inbound channel. `ChatContext` uses a reducer (not
`useState`) and delegates the connection to [chatApi.js](frontend/src/services/chatApi.js), which
points SockJS at `window.location.origin` — correct for the single-origin monolith, so only a chat
server on a separate host would need a configured URL rather than the page's.

**Chat is the board's conversation, and until `V18` it was the one feature the boards-with-members
model never reached.** The destination was built out of a `roomId` the client supplied — so any
string named a topic — and an empty one named `/topic/public`, a single global room every
signed-in account subscribed to on connect, where a member of one board read the messages of every
other board's members. `sendPrivateMessage` had the matching hole from the other side, addressing
whatever `recipientId` it was handed and walking straight past the peer scoping `GET /api/users`
exists to enforce. Neither is addressable now, and six things carry the replacement:

- **A board message travels `/topic/boards.{id}.chat`**, which is the board's *own* destination
  with a suffix rather than a prefix of its own — so `BoardSubscriptionInterceptor` authorises it
  with no second check to keep in step. That interceptor's `boardIdIn` reads the **first** segment
  after the prefix for exactly this reason; it used to parse the whole suffix as the id, and its
  Javadoc said chat's topics "must pass through untouched", which is a fair description of how a
  feature with no tenancy survives in a repository that checks everything else. A dot, not a
  slash, for the RabbitMQ reason `BoardEventPublisher.DESTINATION_PREFIX` states at length.
- **A direct message reaches a peer and nobody else**, checked against `BoardService.peersOf` —
  the same collection `GET /api/users` is built from, so the two cannot disagree about who is
  reachable.
- **Nothing in `ChatController` throws.** An exception on the inbound channel becomes a STOMP ERROR
  frame and a *closed session*, so a paste over the 2 000-character limit did not bounce the
  message — it dropped the connection, and the client reconnected into a room it had to rejoin.
  That is the failure `BoardSubscriptionInterceptor` had already written its reasoning down for,
  one channel over. A refusal is a dropped frame and a WARN.
- **A refusal the sender already knows about is answered; one that turns on who they are is not.**
  Blank and over-long come back on the sender's own `/user/queue/errors` as a `ChatRefusal`
  carrying a **translation key, never a sentence** — the activity feed's rule, for the same reason.
  A board the caller may not post to is silence, or the refusal would confirm the board is real.
  `ChatRefusalKeysExistTest` is the guard on the two-file coupling that creates: the key is chosen
  in Java and rendered from `frontend/public/locales`, in all nine bundles, and nothing else can
  see a rename on either side — the client never names these keys, so `i18n.test.js` cannot.
- **`BoardScopedStompRoutesTest` is `BoardScopedRoutesTest`'s rule one channel over**: every
  `@MessageMapping` handler takes the caller it has to check. The REST scan has held that surface
  honest since the tenancy model landed and nothing scanned STOMP, which is the gap chat grew in —
  a guard that covers one transport and not the other is a guard that says where the next hole
  will be. There is no `PublicPaths` to be excused by here: the channel is authenticated at
  CONNECT, so the rule has no exceptions.
- **A user destination is subscribed to without a name, and that was a live bug.**
  `/user/queue/messages` is the whole destination: Spring reads the account off the session it
  authenticated at CONNECT and rewrites the subscription to a queue of the session's own.
  `chatApi.js` was subscribing to `/user/{email}/queue/messages` — which is the *sending* form —
  so the subscription bound to a queue nothing publishes to. Measured on the compose stack: the
  broker was holding the frames in `messages-user<session>` with no consumer, which is to say
  **no direct message had been delivered since the STOMP relay replaced `enableSimpleBroker`**,
  and nothing errored to say so. Neither suite could see it — Jest asserts a subscription was
  made to a string, and the backend suite asserts `convertAndSendToUser` was called — so it took
  running the stack. `chatApi.test.js` now asserts no subscription names an account.

- **Presence is sent and not stored.** "X joined" is worth a line in the panel while somebody is
  looking and is not worth a row in the scroll-back, where a reconnecting client would bury the
  conversation under its own comings and goings. `WebSocketEventListener` announces a LEAVE on the
  board the session joined and on nothing otherwise; it used to fall back to `/topic/public`, so a
  disconnect told every account on the deployment.

**The messages are readable at last, which is what makes writing them worth anything.**
`ChatRepository` had an empty body and no route read the table, so every message was persisted to
be unread: the panel showed only what arrived while it was open, and the rows accumulated forever
in the database's storage, its backups and its restore time — line for line the charge this file
levels at the `files` table when it calls it "the counter-example rather than the model". Chat was
the second instance. `ChatHistoryController` serves two reads, because there are two kinds of
message and one page holding both would answer neither question: `GET /api/chat?boardId=` is the
board's conversation and `GET /api/chat/direct?with=` is the thread with one peer, in both
directions. Both are paged with the activity feed's numbers and its refusal — 25 by default, 100
at most, and `400 INVALID_CHAT_REQUEST` rather than a silent clamp. A `with=` naming somebody who
shares no board is `404`, the same answer an address with no account gets.

`ChatHistoryService.pruneExpiredMessages` is the other half: a conversation nobody can read
accumulates forever and one anybody can read still does. It runs nightly at 03:30, off the hour the
deadline sweep and the outbox relay use, and is deliberately **not** claimed the way those two are
— a row deleted twice is a row deleted once, so a second replica running it concurrently is wasted
work rather than a defect. `app.chat.retention-days` (90) is a plain property with a default rather
than an environment variable, because a variable Terraform would have to pass, docker-compose
repeat and `ConfigurationTest` audit is a cost a window no environment wants to differ on does not
earn.

`V18` is where the schema caught up: `chat_messages` gained a nullable `board_id` and lost
`room_id`. **Exactly one of `board_id` and `recipient_id` is set** on any row the application
writes. The rows already in the table are left alone rather than backfilled — a `room_id` of
`general` is not a board id and no honest mapping exists — so they keep a null `board_id`, which
means the read routes never return them, which is what they already were. `BoardService.deleteBoard`
clears them by hand alongside the invitations and the activity rows, for the same reason: nothing
cascades, and the foreign key is one the board could not be deleted around.

On the client the room picker is gone — it offered `general`, `help` and `random`, which named
topics the server had no opinion about. The board comes from `KanbanContext`, so switching boards
switches the conversation and there is no second control to find; opening the panel loads page 0 of
the history and "load older" pages back through it.

### Live board sync

A board is the same board for everybody on it, so a change one person makes appears on the others'
screens without a reload. The server announces it on **`/topic/boards.{id}`** — a dot, not a slash:
the STOMP broker relay forwards the destination to RabbitMQ's STOMP plugin as-is, and RabbitMQ
parses everything after `/topic/` as one AMQP routing key, refusing the whole destination the
moment it contains a further `/` — and the client re-reads; `board/event/` holds the publisher,
`config/websocket/BoardSubscriptionInterceptor` holds the check on who may listen, and
[boardEvents.js](frontend/src/services/boardEvents.js) holds the client's own connection.

Six decisions carry it, and none of them is visible from the destination name:

- **The frame carries a kind and a board id, never the thing that changed.** A STOMP topic has no
  per-subscriber filtering - every subscriber of a destination gets every frame - so whatever is in
  the payload is readable by everyone on it, and keeping it to `{type, boardId}` means the
  subscription check is the only thing that has to be right rather than the payload as well. The
  second reason is drift: a DTO published here would be a second copy of the read model, free to
  disagree with what `GET /api/tasks` returns. A kind says "re-read", and the re-read goes back
  through the route that already decides what this caller may see.
- **It carries no actor either**, so nobody can filter out "their own" event. An account is not a
  client: the same account in a second tab is a different screen that does need the frame. The
  duplicate read is made cheap by coalescing instead, which is right in both cases.
- **A subscription is authorised, because the broker does not do it.** RabbitMQ's STOMP plugin
  relays frames; it has no notion of a board and cannot tell one destination from another that is
  wider or narrower, so `WebSocketAuthInterceptor` answering whether a caller is anybody is not
  enough on its own — without `BoardSubscriptionInterceptor` a subscriber holding any valid token
  could sit on any board's topic. It asks `BoardService.requireVisible`, so "may this caller see
  this board" is answered in the one place the REST routes answer it. **Order is load-bearing**: the
  authentication interceptor runs first, because it is what puts the principal on the session.
- **A refused subscription is dropped, not refused out loud**, which is the 404-not-403 rule kept in
  the one form STOMP allows. Throwing is the obvious thing and is worse twice over: Spring turns an
  exception on the inbound channel into an ERROR frame and *closes the session*, so a member removed
  from a board while watching it would reconnect, resubscribe, be closed, and do it again every five
  seconds — and the refusal itself tells a caller the board is real. Returning `null` from `preSend`
  discards the frame: the connection survives with its other subscriptions intact, and a board the
  caller may not see looks exactly like a board where nothing is happening. The cost is that a
  misconfiguration here would look like a feature that quietly does nothing, so every drop is logged
  at WARN and that log is the diagnosis path.
- **The frame is sent after the commit, and that is correctness.** Published inside the transaction,
  it can reach a subscriber whose re-read then beats the commit and returns the state from *before*
  the change - leaving that client permanently stale, because it has spent its only notification.
  The same ordering means a rolled-back transaction announces nothing.
- **One frame per board per kind per transaction.** Reordering a cell of twelve cards saves twelve
  tasks and needs one re-read, so the events are collected against the transaction and sent once;
  the client then coalesces a burst into one fetch (`LIVE_REFRESH_WINDOW_MS`). Otherwise the
  busiest gesture on the board would be the noisiest thing on the wire.
- **It is a second connection, not chat's.** `ChatContext` connects when somebody opens the chat
  panel and disconnects when they close it, so a board riding that connection would stop updating
  the moment anyone tidied their screen and would never start for people who never open chat. The
  board client renews its token in `beforeConnect` rather than in the constructor, because the
  CONNECT frame is the only place the fifteen-minute token is checked and a socket that drops an
  hour later would otherwise retry with a dead token every five seconds forever.

**A mutation that forgets to announce is silent**: the row is written, the response is right, every
unit test passes, and only somebody *else's* screen is wrong. So `TaskService`, `ColumnService` and
`RowService` each funnel their save-and-map through a private `saveAndAnnounce`, and
`BoardEventCoverageTest` reads their source and fails the build when a save-and-map appears outside
it - turning "somebody forgot a line" into "somebody wrote a different method call", which is a
thing a check can see. Deletes map nothing, so they are named in that guard separately.

**The cross-replica half is tested by a spec that needs two replicas to exist.**
`cypress/replicas/cross-replica-sync.cy.js` opens the board through nginx — whose upstream is the
compose service `app` and which has never heard of `app2` — so the document's SockJS connection,
its CONNECT and its SUBSCRIBE are all held by one replica, and then makes every change against the
*other* replica's own port. A card that appears crossed two JVMs and the broker between them;
there is no path that does not. It is outside Cypress's default `specPattern` rather than skipped
when a second replica is missing, because a spec that quietly does nothing is the failure
`external-scan.yml` spent a month being — `npm run cypress:run:replicas` runs it and
`kanban-ci.yml`'s e2e job runs that as a step of its own. What would silently turn it back into a
same-replica test is its address drifting onto `app`'s published port, one digit away;
`CrossReplicaStackTest` reads the compose file, the spec, `package.json` and the workflow and fails
the build when any of the four stop agreeing.

There is deliberately **no toast** for a remote change: a card moving under somebody is worth
showing and not worth interrupting them over, and `/activity` is the screen that answers who did it.

### Configuration and secrets

`application.properties` resolves everything from environment variables and imports `optional:file:.env[.properties]`, so a `.env` in the backend working directory supplies local values (template: `backend/.env.example`). `KanbanConfig` additionally loads dotenv directly via `io.github.cdimascio:dotenv-java`. `.env` files are gitignored.

**Configuration is audited at build time, in both directions (`ConfigurationTest`).** Environment
variables are the one coupling in this repo with no compiler on either side: a variable the
application requires and an environment never sets is a container that will not start, and a secret
an environment supplies that nothing reads is a lie that survives every build. Both have happened
here - the `SPRING-MAIL-*` Key Vault secrets outlived the code that read them by two revisions
(MAIL-02), and `CAPTCHA_SECRET` reached a verifier that did not exist (SEC-06). The test reads four
files and compares them: the `${VAR}` placeholders in `application.properties`, the
`@ConfigurationProperties` records, `docker-compose.yml` and
`terraform/modules/container_app/main.tf`.

Two things about it are worth knowing before adding a variable:

- **A variable can be read without appearing in `application.properties`.** Relaxed binding means
  `SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT` reaches `AuthRateLimitProperties.trustedProxyCount`
  with nothing in between, which is how Terraform sets it. The test derives those names by
  reflection over the four records in `BOUND_PROPERTIES`; a record that is added and not listed
  there has its variables reported as dead configuration.
- **The `env` blocks in the container-app module are the audited list, not the Key Vault secret
  names.** A secret may exist in the vault without being passed to the app - that is what a rename
  in flight looks like - so the check is on what the container is handed.

**Mail goes out over the Azure Communication Services Email API, not SMTP.** [EmailConfiguration](backend/src/main/java/pl/myproject/kanbanproject2/config/EmailConfiguration.java) builds an `AcsEmailSender` from `app.mail.*` — a connection string and a MailFrom address the linked domain actually has. There is no `spring-boot-starter-mail` and no `jakarta.mail` on the classpath; the `PersistentSmtpMailSender` that used to hold one Gmail connection open, ping it every four minutes and tell a dropped link from a rejected message is gone, and so is every knob that tuned it. The deployment is on Azure, a personal Gmail account is not a sending quota to build on, and an HTTPS request has no session to keep alive.

Three things about it are load-bearing:

- **`EmailService` composes, `EmailSender` transports.** Callers name a message — `sendVerificationCode`, `sendPasswordResetCode`, `sendTaskOverdue` — and `MailTemplates` writes it; they no longer assemble HTML themselves, which is what three near-identical copies of the same markup used to mean. Every message is an `EmailMessage` carrying **both** an HTML and a plain-text body, because a `text/plain` alternative part that is optional is one that gets left out; everything interpolated goes through one escape, including values that cannot currently contain markup. The wording lives in `backend/src/main/resources/mail/messages*.properties`, **one bundle per locale**, and every method on `EmailService` takes the recipient's `Locale` — see the i18n section. Callers see `EmailService` and an `EmailDeliveryException`; which provider carries the message is behind the interface. That seam is where the queue went: `OutboxEmailSender` is `@Primary` and writes a row, `EmailConfiguration` now builds the transport under the bean name `mailTransport`, and `OutboxRelay` is the only thing that asks for it by name. `OutboxWiringTest` fails the build if either annotation goes — two `EmailSender` beans with no primary is a context that does not start, and a swap would have the relay posting rows into the queue it is meant to drain.
- **`beginSend` has already posted by the time it returns.** The returned `SyncPoller` is dropped on purpose: `SyncOverAsyncPoller` runs its activation — the POST — inside its own constructor, so the message is with Azure and anything it objected to has already been thrown. Polling further would wait on *delivery*, which no request has any use for. The cost is that a message accepted and bounced later is reported nowhere; a delivery-report subscription on the resource is what would surface it, and there is not one yet. `AcsEmailSenderTest` drives the real SDK over a fake transport and fails if activation ever stops being eager.
- **The Netty transport is excluded in favour of `azure-core-http-jdk-httpclient`.** The SDK finds its HTTP client through a `ServiceLoader` at runtime, so nothing about that choice is visible to the compiler — `AzureTransportTest` asserts the resolved client is the JDK one and that Netty is not on the classpath at all. Versions come from the `azure-sdk-bom`; do not pin the Azure artifacts by hand.

With no connection string the bean is a `DisabledEmailSender`: the app starts and drops messages instead of refusing to boot, which is what lets CI and a fresh clone run without an Azure account. Nothing about a dropped message is logged — subjects carry task titles and bodies carry live verification codes, and the last rewrite of this configuration happened because `mail.debug` had been left on and was writing the SMTP dialogue to the application log. The startup warning naming the missing properties is the only signal, deliberately.

**Terraform provisions the ACS secret, not the ACS resource.** `terraform/` now writes an `ACS-EMAIL-CONNECTION-STRING` Key Vault secret (from `acs_email_connection_string`, empty by default) and passes `ACS_EMAIL_CONNECTION_STRING` / `ACS_EMAIL_SENDER_ADDRESS` to the container app — the `SPRING-MAIL-*` secrets are gone. What Terraform still does **not** create is the Communication Services resource itself or the email domain linked to it; those are made by hand in Azure (an Azure-managed `*.azurecomm.net` domain needs no DNS), exactly as the Gmail account this replaced was. Left empty, the app boots with mail disabled rather than failing.

**Mail is enqueued, not sent, on the request thread (`V10`).** `email_outbox` holds one composed
message per row — both bodies, exactly as `MailTemplates` built them, because re-running the template
at send time would mail a wording change to somebody who was queued before it. The row is written in
the caller's transaction, which is why `signup` and `resendVerificationCode` are now `@Transactional`
and save the account **before** the message rather than after: an account and the mail announcing it
either commit together or neither happens. Writing to Service Bus instead would not have that
property — a queue is a second system too, and an enqueue after the commit is lost if the process
dies in between; the outbox is what makes a later Service Bus hop safe rather than what it replaces.

`OutboxRelay` runs every 60 seconds beside the deadline sweep, takes the 50 oldest due rows, and
posts them one at a time. It is deliberately **not** transactional: a transaction around the loop
would hold a connection across fifty HTTPS round trips and roll back the record of forty-nine sent
messages because the fiftieth was refused. A refusal waits — one minute, doubling — and after five
attempts the row is `FAILED`, which is the dead letter. With
no mail account configured the relay marks rows `DROPPED` rather than `SENT`, via
`EmailSender.deliversMessages()` — the one table whose job is to be truthful about mail must not
claim a fresh clone sent anything.

**A row is claimed before it is posted, so a second relay cannot post it too.** `OutboxClaimer`
selects `FOR UPDATE SKIP LOCKED` and marks the batch `SENDING` in one short transaction that
commits before any HTTPS call; the relay then posts outside it, exactly as before. Four things
carry it, and none is visible from the query alone:

- **The lock partitions the batch; the status keeps the partition.** A row lock lives only as long
  as its transaction, and that transaction cannot last as long as the send — which is the same
  reason the relay is not transactional. So the lock is what makes two simultaneous selects
  disjoint, and `SENDING` is what keeps them disjoint afterwards, because the claim query asks only
  for `PENDING`. `SKIP LOCKED` rather than a plain `FOR UPDATE` because the second relay should
  take the *next* fifty rows, not block behind the first and drain the queue at one replica's
  speed however many are running.
- **The claim is its own bean because Spring's transactions are proxies.** A `@Transactional`
  private method of `OutboxRelay` would be called on `this`, run with no transaction at all, and
  read perfectly — which is the failure it exists to prevent, since the lock would then be released
  before `SENDING` was written. `OutboxClaimQueryTest` asserts the annotation and that the methods
  are `public`, because `@Transactional` on a package-private method is ignored without a word.
- **A claim carries a lease, in `next_attempt_at`.** A relay killed mid-batch leaves rows nothing
  intends to post; ten minutes later the next pass takes them back to `PENDING` and says so at
  WARN. The lease is not a timeout on one send — every row in a batch is claimed at the same
  instant and the fiftieth is posted last. Reclaiming **charges an attempt**, which is the only
  thing bounding a message whose own content kills the relay: five lapsed claims make it a dead
  letter carrying the same `MAIL_DEAD_LETTER` marker as a refusal.
- **It is at-least-once, deliberately.** A row already posted when the process died is posted again
  after its lease lapses. The other ordering — mark sent, then send — trades that duplicate for a
  verification code nobody ever receives, and a code arriving twice is the better failure.

The query is native, and that costs something worth naming: `QueryStringsResolveTest` compiles the
hand-written *HQL* and skips native queries, so nothing but a database checks this string.
`@Lock(PESSIMISTIC_WRITE)` with a `jakarta.persistence.lock.timeout` hint of `-2` is the portable
spelling and fails worse — a hint the provider does not honour is dropped silently, leaving a
blocking `FOR UPDATE` that looks identical until two replicas run. `OutboxClaimQueryTest` pins the
clause instead.

`MailHealthIndicator` gained a `sending` detail with it: a claimed row is no longer `PENDING`, so
without it a queue being worked reads as a queue that is empty, and a relay killed mid-batch reads
as nothing at all until the lease lapses.

The other constraints named here when this paragraph was written are addressed now too: the
deadline sweep claims its own rows the same way, `AuthRateLimiter`'s escalation lives in Redis
rather than each replica's own process memory (see the auth section below), `WebSocketConfig`
relays to a real broker rather than holding one in the JVM (see **Chat** and **Live board sync**),
and `TaskAttachmentService`'s transfer semaphore divides by a replica-count hint instead of reading
as a per-replica limit (see **Attachments**). This — the outbox claim — went first because its
failure mode is the one that leaves the building. With all five cleared, phase 4 of the container
split raised `api_max_replicas` from 1 to 5 in `dev.tfvars`, matching `web_max_replicas` rather than
a new ceiling picked without a reason — this deployment has no data yet suggesting the API needs a
different one than the edge does. Applied to dev, `az containerapp show` confirmed `max_replicas=5`
on `kanban-api-dev`, and `cypress/replicas/cross-replica-sync.cy.js` (see **Live board sync**) is
the automated version of the live two-replica check `web_max_replicas` needed before it moved.

**Two things watch the dead letters, because moving the send off the request thread moved the
signal with it.** A refusal used to be a `500` on `/api/auth/register`, which the `http_5xx` alert
already fires on; it is now a row, and a dead-letter queue nobody watches is a silently dropped
mail with extra steps. `MailHealthIndicator` answers on `/actuator/health` under the key `mail`:
`OUT_OF_SERVICE` when no mail account is configured (with the `DROPPED` count, which is what that
costs), `DOWN` when the relay has given up on a message in the last 24 hours, `UP` otherwise, with
the queue depth as a detail. **It cannot take the deployment down** — the Dockerfile and all three
Container Apps probes address `/actuator/health/readiness` and `/actuator/health/liveness`, the two
*groups*, and a plain indicator joins neither; mail being off is a reason to tell somebody and not
a reason to restart the container. Details are hidden from anonymous callers by
`management.endpoint.health.show-details=when_authorized`, and nothing loads a row, because a
pending row's body is a live verification code.

For the far commoner case of nobody asking, `OutboxRelay` logs `DEAD_LETTER_MARKER` —
`MAIL_DEAD_LETTER` — on the give-up line, and a Log Analytics rule in
`terraform/modules/diagnostics/main.tf` matches it and mails the alert address, next to the 5xx and
restart alerts. It is a token rather than a phrase from the sentence because a reworded log line is
an alert that stops firing without anything failing; `DeadLetterAlertTest` reads the Terraform and
fails the build if the two stop agreeing, which is the only thing that can see that coupling.

What a caller sees change: `POST /api/auth/register` no longer waits for Azure and no longer answers
`500 EMAIL_SEND_FAILED` when the provider refuses. `EmailDeliveryException` still exists and still
reaches the caller, but only when the *row* cannot be written — a database that will not take it is
a signup that was not going to work anyway.

**`SENT` means accepted, and since `V16` a row can say what happened afterwards.** Azure answering
`202` is acceptance; a hard bounce, a spam rejection or an address that does not exist all happen
later and out of band, and the outbox could not see any of it. It can now: Azure publishes a
delivery report per recipient over Event Grid, `MailDeliveryReportController` receives it at
`POST /api/mail/delivery-reports`, and `MailDeliveryReportService` writes the outcome onto the row
the report names. Five things carry it.

- **The join key is written at the moment of acceptance, and getting it costs one extra request.**
  `EmailSender.send` now returns the provider's id for the message and `AcsEmailSender` reads it
  with a single `SyncPoller.poll()` — one `GET` on the operation, never a walk to completion. That
  reverses a decision made deliberately when the transport landed ("a signup has no use for the
  answer and every reason not to hold a request thread open for it"), and the reason is that the
  premise expired: since the outbox, no request thread waits for a send at all. It is best-effort —
  the message is already with Azure by the time the id is read, so an unreadable id is `null` and a
  row that cannot be matched, never a send reported as refused.
- **It is the only unauthenticated write in the application, and it is off by default.** Event Grid
  holds no account here, so the URL is the credential: `app.mail.delivery-report-key`
  (`APP_MAIL_DELIVERY_REPORT_KEY`) must appear as `?key=`, compared with `MessageDigest.isEqual`
  rather than `equals`. Blank — the default, and the state of every fresh clone and CI run — makes
  the route answer `404` to everything, and Terraform creates no Event Grid subscription either. A
  wrong key is the same `404`, for the reason every other "you may not see this" here is a 404.
  `BoardScopedRoutesTest` names it explicitly alongside the auth routes, so a board route losing its
  token check still fails the build.
- **The handshake is answered in the body.** Event Grid will not deliver to an endpoint until the
  endpoint echoes a `validationCode` back, so a `200` with an empty body creates a subscription that
  exists and silently never delivers. Past the key check the route answers 2xx to everything,
  including a report naming a message this deployment never sent — ordinary, for a row queued before
  `V16` or a subscription aimed at another environment — because Event Grid reads anything else as
  "retry".
- **Later reports win, on the provider's clock rather than on arrival.** One message produces
  several reports and a retry can overtake what it is retrying; taking the last to arrive would let
  `OutForDelivery` land on top of `Delivered`. `delivery_reported_at` is the comparison.
- **`delivery_status` stores the provider's own word** — `Delivered`, `Bounced`, `Failed`,
  `Quarantined`, `FilteredSpam`, `Suppressed` — rather than an enum of this application's invention,
  and an unrecognised value is stored rather than refused. Which of those mean "did not arrive" is
  written down twice, in `MailDeliveryStatuses.UNDELIVERED` and in the Log Analytics bounce alert's
  KQL, and `BounceStatusesMatchAlertTest` fails the build when the two disagree —
  `DeadLetterAlertTest`'s shape, on a rule that has already been wrong once (the alert's first draft
  omitted `Bounced` itself).

The Terraform half is an `azurerm_eventgrid_system_topic` on the Communication Services resource
(`location = "global"`, because ACS is global) and one subscription whose URL the diagnostics module
builds from the **web** app's FQDN plus that key — so the address and the credential cannot be
configured into disagreeing. It has to be the web app since the split: Event Grid calls the URL from
outside this VNet and cannot reach an internal ingress at all, and nginx proxies
`/api/mail/delivery-reports` like any other `/api` path, for free. It needs both
`acs_communication_service_id` and
`mail_delivery_report_key`, and it is **the one resource here with an ordering constraint against
the application rather than against other Terraform**: Event Grid validates the endpoint by calling
it at creation time, so the app has to be deployed and serving before this can apply.

**Attachment storage takes one of two ways in, never both.** `app.storage.endpoint` with no key is production — a managed identity against an account with shared-key access off — and `app.storage.connection-string` is a local Azurite container. Neither set turns attachments off rather than failing to boot. `AZURE_STORAGE_IDENTITY_CLIENT_ID` is read as a property rather than left to the SDK's own `AZURE_CLIENT_ID`, because a variable Terraform passes and nothing in this repo binds is one `ConfigurationTest` reports as dead configuration — and it would be right to.

**The connection pool is sized for the fleet, not for one replica.** HikariCP defaults
`maximum-pool-size` to 10 and `minimum-idle` to *whatever that is*, so an idle replica does not
merely allow ten connections — it holds ten. At `api_max_replicas = 5` that is fifty held at rest,
and the dev server has fifty in total of which ten are reserved: measured, not assumed, with
`az postgres flexible-server parameter show` reporting `max_connections = 50` and
`superuser_reserved_connections = 10`. Forty usable, fifty asked for — the fleet could not reach
its own replica ceiling, and the first sign would have been the `postgres_connections` alert firing
on refused connections, an alert whose description already names this mechanism. The alarm was
wired and the limit was not.

`api_db_connection_budget` is fleet-wide for the same reason `app.storage.max-concurrent-transfers`
is: what runs out is on the server, so a per-replica limit silently means that number times the
replica count. `modules/api_app` divides it by the same `var.max_replicas` it already feeds the
attachment semaphore, so the two cannot drift; `DB_MIN_IDLE` stays at the property default of 2,
which is the half that decides what an idle fleet holds.

**Where the ceiling itself lives is the part with no natural home.** Azure sizes `max_connections`
from the SKU and publishes it as no attribute of the resource, so `modules/postgres` records it as
a map and exposes `usable_connections` — the SKU's limit less the superuser reserve — with an
unlisted SKU a plan failure rather than a `lookup()` default, because being wrong optimistically
here *is* the outage. The root module compares the budget against it on every plan, and
`DatabasePoolBudgetTest` makes the same comparison on every build: a Terraform `check` block warns
rather than fails and only runs where credentials do, which is not where this repository holds the
rest of its two-file rules.

**Flyway owns the schema; Hibernate only validates against it** (`spring.jpa.hibernate.ddl-auto=validate`). Migrations live in [backend/src/main/resources/db/migration/](backend/src/main/resources/db/migration/) and run at startup.

`V5__add_boards.sql` is the one to read before adding another: it adds a column, backfills it, and
only then makes it `NOT NULL`, in that order, because any other order fails against a database that
already has rows. It puts everything an existing deployment already had onto one board and makes
every existing account a member of it, which is precisely the arrangement those accounts had before
— one shared board — except that it is now written down and checked.

A schema change is therefore two edits, not one: the entity, **and** a new `V<n>__description.sql`. `ddl-auto=validate` will not add a column for you — it refuses to start without it, which on Container Apps is a revision that never becomes healthy. `FlywayMigrationsMatchEntitiesTest` regenerates the DDL Hibernate would emit and fails the build when an entity has moved on without a migration, so that mismatch is caught at build time rather than at startup.

**Two branches that each add a migration have an order between them even when they share no line of code**, and it is invisible to `FlywayMigrationsMatchEntitiesTest` (which compares names, not history). Merge them the wrong way round — a lower `V<n>` appearing under a higher one already applied — and the build stays green while the *next* deploy migrates and then refuses. The `migration-order.yml` PR check is the guard: it reads the highest migration version on the base branch and fails a PR that adds one at or below it, so a branch that has fallen behind `main` must renumber before it can merge. `MigrationOrderTest` is the database-free companion that catches a duplicate or a gap left by a sloppy merge; it cannot see the deploy-order trap and says so in its own Javadoc.

**Postgres indexes no foreign key, so `V19` adds the sixteen that had none and
`ForeignKeysAreIndexedTest` keeps it that way.** Unlike MySQL, Postgres indexes the side a key
points *at* and leaves the referencing column bare — so each unindexed one was a sequential scan
on "which rows point at this", plus a full scan of the child table on every delete or update of a
referenced row. The nine indexes the earlier migrations declared were each added for a query
somebody had in hand; these are the set nobody had a reason to add yet, read out of the foreign
keys themselves. Four are on constant paths: `board_members (user_id)`, whose composite primary key
leads on `board_id` and so cannot answer "which boards is this account on"; the same asymmetry on
`user_task (user_id)`, behind `UserService.checkWipStatus`; `task_labels (task_id)`, an
`@ElementCollection` with no key at all; and `task_column_history (column_id)`, which `V17` taught
`ColumnService.deleteColumn` to write by.

The guard is worth more than the migration, because the migration is a one-off and the next foreign
key is not. It parses the migrations, collects every foreign key column and every index's **leading**
column, and fails the build on one with no cover. Two of its rules are load-bearing and neither is
obvious: only the leading column of a composite counts — reading `(board_id, user_id)` as covering
both is exactly the mistake that left the membership check unindexed — and **a partial index covers
nothing**, which is why `ux_board_invitations_pending` (`WHERE status = 'PENDING'`) does not answer
`BoardService.deleteBoard`'s lookup by board. It carries its own control case, so a parser that has
quietly stopped matching cannot pass as a schema with nothing to find.

`V1__baseline_schema.sql` is the schema as `ddl-auto=update` left it, generated from the entity mappings under **Spring Boot's** naming strategies rather than Hibernate's bare defaults — that is the difference between `recipient_id` and `recipientId`, and between the `task` table and `Task`. `spring.flyway.baseline-on-migrate=true` means an environment that already has that schema is marked at V1 without re-running it, while a fresh database runs it like any other migration.

`backend/db.sql` is gone. The default columns it seeded are `V3__seed_default_columns.sql`, so local development gets them by the same route as every other environment — and an init script would have left the volume non-empty, which is exactly the state `baseline-on-migrate` reads as "already migrated".

**Captcha is verified server-side, and it takes two variables that have to be set together.**
`CaptchaVerifier` checks the token against Google's `siteverify` when `security.captcha.enabled` is
on; a missing token is a failure rather than a skip, an unanswerable check fails closed, and enabled
with no secret refuses to start. `CaptchaCoverageTest` fails the build if a credential route accepts
a token nothing checks.

The pairing is the part worth writing down, because neither half is any use alone and nothing at
runtime can tell you the other is missing. **`VITE_RECAPTCHA_SITE_KEY` is baked into the bundle at
Vite build time** (a Docker `ARG`, so it is fixed when the image is built and cannot be changed by
restarting the container), and **`CAPTCHA_SECRET` is read by the server at startup**. Set the secret
and enable the check with no site key and the widget never renders, so every login arrives with no
token and fails closed - the control locks everybody out instead of protecting anything. Set the
site key with `CAPTCHA_ENABLED=false` and the widget renders and is decorative, which is what
SEC-06 was. `ConfigurationTest` audits which environments supply what; that the two agree in
*meaning* is not something any test here can see, so it is written here instead.

### CI/CD and infrastructure

- `kanban-ci.yml` — on PRs and pushes to `main`: backend job runs `mvnw clean verify` against a Postgres service container (writing a `.env` from secrets first), which is the phase the JaCoCo `check` gate is bound to; frontend job builds, lints (**blocking** — the `continue-on-error` escape is gone) and runs Jest with coverage; and a third **`e2e` job** brings the `docker-compose` stack up (mail and captcha off, `AZURE_STORAGE_CONNECTION_STRING` empty), seeds a test account via `npm run cypress:seed`, and runs Cypress headless against the built bundle on `:8080`. Cypress *is* run in CI now. That stack comes up with **`--profile replicas`**, so the whole suite runs against two API replicas rather than one, and a further step runs `npm run cypress:run:replicas` — the cross-replica board-sync spec, which needs the second one. The step between them asserts `app` and `app2` really are two containers, because one container answering both ports would make that spec a slower copy of `live-sync.cy.js`, passing and proving nothing.
  A fourth **`image-scan` job**, matrixed the same way `kanban-cd.yml`'s `build-and-push` is, builds
  both Dockerfiles locally (`load: true`, nothing pushed to GHCR) and runs the same Trivy gate
  `kanban-cd.yml` runs after merge — same severities, same `ignore-unfixed`, same exit code — so a
  base image that has picked up a fresh CRITICAL/HIGH between a PR opening and merging goes red on
  the PR itself rather than on `main` a day later, which is the shape #160 was.
  **It also runs on a daily `schedule` (and `workflow_dispatch`), which is the only trigger that
  covers a merge.** A push made with the default `GITHUB_TOKEN` starts no workflow run, and
  `dependabot-auto-merge.yml` merges with exactly that token — so an auto-merged dependency PR
  reaches `main` with nothing having run the suite against the *merged* tree. That is not
  hypothetical: it is how `react` and `react-dom` ended up on different versions with every React
  suite failing to start — green on the Dependabot branch, because the break belongs to the merged
  lockfile and to neither side of it, and found an hour later only because it broke somebody
  else's pull request. The `push` trigger is not wrong; it is simply never reached on that path.
  The sweep's `trunk-alarm` job is the half that tells somebody: it opens, comments on, or closes
  a `trunk-red` issue, and fires on the sweep triggers only, because a red pull request already has
  an author watching it. It reads `skipped` as red, deliberately — a job that did not run proved
  nothing. The script itself lives in **`sweep-alarm.yml`**, a `workflow_call` workflow four sweeps
  now share; see below.
- **`sweep-alarm.yml`** — the alarm, once, for every workflow that runs with nobody watching it.
  The caller passes the results it collected and a phrase for the title; `github.ref_name`,
  `github.workflow` and the run URL come from the context, which inside a reusable workflow is the
  *caller's*. One issue per ref per sweep — per ref because a dispatch can sweep a branch and a
  green branch sweep must not close the issue saying `main` is broken, per sweep because a red DAST
  run and a red image scan are two different things to go and fix. **It assigns the issue it
  opens** (`vars.SECURITY_CONTACT`, falling back to the repository owner), best-effort so a failed
  assignment cannot lose an issue that was successfully opened: an unassigned issue notifies
  nobody, which is the whole failure this exists for rather than a detail of it.
  **Three couplings hold each caller together and nothing but a test can see any of them.** The
  alarm is only as wide as its `needs` list — a job added to a workflow and left out of that list
  fails while the alarm still reports success. It is only as wide as the `results` string, which is
  new with the reusable workflow: the alarm no longer reads `needs` itself, it reads a string the
  caller builds out of it, so a job in `needs` and not in that string is the same defect one level
  further in. And it is only as wide as the triggers its `if` names, which is how the whole alarm
  was once unreachable except by a red cron. `SweepAlarmCoverageTest` reads all five workflows and
  fails the build when any of the three disagree — the same rule-in-two-files-checked-in-one shape
  as `DeadLetterAlertTest`. **Its list of sweeps is maintained by hand**, so a new scheduled
  workflow added and not listed there is not covered; that is stated rather than solved, because
  guessing from the trigger block would silently cover workflows that were never meant to alarm.
- `kanban-cd.yml` — on pushes to `main` **and on a daily sweep at 05:47 UTC**: builds
  `backend/Dockerfile` and `frontend/Dockerfile`, pushes them to
  `ghcr.io/<owner>/kanbanproject-app` and `ghcr.io/<owner>/kanbanproject-web` tagged with the commit
  SHA, and scans each with Trivy
  (CRITICAL/HIGH, SARIF to the Security tab) before a separate `promote` job re-tags both `latest` —
  a scan failure, a ref that is not the default branch, or a commit that's no longer the tip of it
  blocks promotion, so `latest` is always a SHA on `main` that both built clean and passed the scan.
  **The two images are one matrix job rather than two jobs, and that is load-bearing rather than
  tidy**: `cd-alarm` has to name every job in `needs` *and* in the `results` string it passes, and
  `SweepAlarmCoverageTest` fails the build when the two disagree — a matrix leg is not a job by that
  reckoning, `needs.build-and-push.result` aggregates every leg, so the matrix can grow without a
  third place to remember. The SARIF category and the SBOM artifact name carry the image name, or
  the second leg silently replaces the first's findings. **`promote` moves both or neither**, and it
  resolves the SHA tag rather than a digest — a matrix job's outputs are whichever leg wrote them
  last, and Terraform feeds one `app_image_tag` to both container apps, so a `latest` that is half
  one commit and half another is precisely the skew the split introduced, with nothing to say so.
  **The ref half of that was missing until the sweep landed**: the check asked only whether the
  commit was the tip of `github.ref_name`, which on a `workflow_dispatch` against a feature branch
  is perfectly true — so dispatching CD on a branch would have tagged that branch's build `latest`.
  Nothing ever did. Adding a schedule was the reason to make sure nothing can. Each leg also emits a CycloneDX SBOM
  (uploaded as a build artifact) and, with `id-token: write` for keyless OIDC, cosign-signs its image
  and attests the SBOM against it — both addressed by digest, not tag, so they can't drift onto a
  later build of the same tag.
  **The sweep is here for the same reason it is on CI, and it was measured before it was added.**
  A `GITHUB_TOKEN` merge starts no run here either, and on 12 Sep 2026 five consecutive merge
  commits (`974b764`, `f8817f7`, `b5c8f11`, `ed9d463`, `549e343`) reached `main` with no CI, no
  CodeQL and no CD behind any of them. On CI that gap is an untested trunk and the sweep already
  covers it; here it is quieter and reads as the opposite of a problem — no image is built for
  those commits at all, so `latest` stops being the tip of `main` and looks exactly like a
  deployment nobody has made yet. A daily rebuild of the tip is what makes `latest` converge again
  whoever did the merging, and `cd-alarm` is what says so when it cannot. The rebuild is cheap and
  idempotent: the SHA tags are the same tags, and `promote` re-tags `latest` at the same images.
- **`deployed-contract.yml`** — a daily sweep that asks the deployed origin whether it still
  answers what the trunk claims. It is the one direction nothing else here covers: every other
  guard reads source and compares it with source, Checkov reads what Terraform declares, and
  `terraform plan` reads what Terraform declared last time — **all of which stay green on an
  environment running eight-day-old code**, which is precisely what TF-09 was. The claims are read
  out of the Java at run time rather than copied into the script (`SecurityHeaders`'s two policy
  constants, `SpaRoutes.ALL`), because a copy is the drift every guard here exists to catch; a
  source file the script cannot parse is an error, never a skipped check. It needs no credentials
  — only the `DEPLOYED_ORIGIN` repository variable — and an unset origin fails rather than skips.
  **What it does not do is name the deployed commit**: nothing public says which one is running, so
  a revision that moves no claim is invisible to it. That is stated rather than solved.
- `codeql.yml` — CodeQL analysis of the Java backend, on pushes, PRs and a weekly cron. The weekly
  cron is why it is not in `SweepAlarmCoverageTest`'s list: its findings go to the Security tab,
  which has its own notifications, and there is no job result an alarm could add anything to.
- `migration-order.yml` — on PRs: fails a branch that adds a Flyway migration numbered at or below the highest version already on the base branch, forcing a stale branch to renumber before it merges (see the Flyway section). Its cheaper companion is the database-free `MigrationOrderTest`, which catches a duplicated or skipped `V<n>` after a sloppy merge.
- `hadolint.yml` — Dockerfile lint, on push and PR, as a matrix over `backend/Dockerfile` and
  `frontend/Dockerfile`. A matrix rather than two steps so a failure names the image it is about,
  and so a third image is a line rather than a copied block.
- `dependency-review.yml` — flags vulnerable/newly-added dependencies on a PR (comment only).
- `dependabot-auto-merge.yml` — auto-merges Dependabot PRs that pass CI, and **only
  `semver-patch` and `semver-minor`**: majors are held for a person. That rule has held every time
  it mattered — Spring Boot 4, azurerm 5 and the jjwt 0.12 API rewrite were each opened by a human
  and each needed source changes they got. The one bump that ever broke `main` was a *correctly
  classified* minor: `react-dom` reads `react`'s version at import and refuses to load when they
  differ, so the package's own semver understated its coupling. `react` and `react-dom` are a
  Dependabot **group** now — there is no pull request that moves one alone — and
  `frontend/src/__tests__/dependencyPairs.test.js` fails the build when the two declare different
  versions, on the branch proposing it rather than an hour later on somebody else's PR. The full
  read of PRs 61–121 is in [docs/dependency-backlog-audit.md](docs/dependency-backlog-audit.md);
  its conclusion is that counting unreviewed PRs was never the useful thing to track.
- `dependency-scan.yml`, `external-scan.yml`, `dast.yml` — scheduled (and `workflow_dispatch`)
  security sweeps: a dependency vulnerability sweep, an external attack-surface scan, and an OWASP
  ZAP DAST run. None gate a PR, and **all three now call `sweep-alarm.yml`**, because a weekly cron
  that goes red on a repository nobody watches is a finding that reaches nobody. `dast.yml` needs
  both halves: `fail_action: false` means the job is green whatever ZAP finds, so the alarm there
  only ever catches the sweep itself breaking, and the findings reach a person through the standing
  `ZAP Scan Baseline Report` issue — **which is now assigned and labelled**, because the first one
  sat open and unread for eight days with the absent `Content-Security-Policy` as its first
  finding. That is the same shape as the dead-letter queue MAIL-04 was filed over, and it cost the
  same. The assignment step is `continue-on-error`, deliberately: the scan has already run and its
  report is already filed by the time it executes, so it must not turn a green sweep red.
  **`external-scan.yml` needed a third thing, which is that it had never scanned anything.** Its
  target came from a `PROD_HOSTNAME` repository variable that was never set — prod was retired
  before it existed — so every step carried `if: skip == false`, every step skipped, and the job
  reported success in five seconds. The alarm could not see it: the job genuinely succeeded. It
  reads `DEPLOYED_HOSTNAME` now (`PROD_HOSTNAME` still honoured), **an unset target is a failure
  rather than a skip**, and the four security-header checks are errors rather than warnings, since
  `SecurityHeaders` now makes each of them a claim the build asserts. The rule — *a sweep that did
  not do its work must not report that it passed*, which is the alarm's own `skipped`-is-red rule
  read one level in — is pinned by `SweepAlarmCoverageTest`, which fails the build on a swept
  workflow that writes a `skip=true` flag or gates a step on one.
- `terraform-ci.yml` — on changes under `terraform/`: `fmt -check`, `init -backend=false`,
  `validate`, then **a blocking Checkov scan**. The scan reads [.checkov.yaml](.checkov.yaml),
  which single-sources the invocation so `checkov --config-file .checkov.yaml` reproduces CI
  exactly — worth having now that a failure stops the build. **Every skip lives in that file**,
  one line each, and the reasoning is here rather than beside them: `CKV_AZURE_41`, because an
  expiry on a secret nothing rotates is a scheduled outage and the honest fix is rotation;
  `CKV_AZURE_136`, because geo-redundancy is `true` in `prod.tfvars` and deliberately false in dev
  and uat, which a scan reading the module default cannot see; and `CKV2_AZURE_57`, because the
  Postgres server is reached by VNet integration (a delegated subnet and a private DNS zone, with
  `public_network_access_enabled = false`), which is the alternative to a private endpoint rather
  than the absence of one. The attachment storage account adds two: `CKV_AZURE_33`, because
  there is no queue service on that account to log, and `CKV2_AZURE_1`, customer-managed encryption
  keys, which is the same trade `CKV_AZURE_41` names — a key nothing rotates buys the appearance of
  control and a scheduled outage.
  **It briefly needed two more and earned both back**, which is the
  shape a skip should take whenever it can: `CKV2_AZURE_33` (private endpoint) went when the app's
  traffic moved onto one, and `CKV_AZURE_59` (public network access) went when the account was
  closed to the internet outright. If either fires again, the account has been reopened somewhere —
  see `terraform/front-door-private-origin.md`, which exists to stop that being the fix. They were briefly `# checkov:skip=` comments on the resource, which is
  the more precise home and the more fragile one: bundled with their justification they read as
  prose, and trimming the prose silently deleted the directive and turned CI red. A `skip-check`
  entry cannot be lost that way. What it costs is breadth — the skip applies to any future
  resource of that kind, and there is one Postgres server. **Checkov's version is pinned** — an
  unpinned scanner on a blocking step goes red on somebody else's release day, and the first
  response to that is always to put the escape back. **There is deliberately no `plan` job.** One
  existed, gated off behind `TF_PLAN_ENABLED` and never wired, and measuring it before wiring it
  showed it could not work: the values that gate `count` on the alerts, the Event Grid pair and
  three Key Vault secrets live in gitignored `<env>.local.tfvars` — TF-07's fix — which CI cannot
  read, so the exact command the job ran against real dev state plans **`0 to add, 2 to change, 15
  to destroy`** where a person's `./tf.sh dev plan` reads **`No changes`**. A permanent
  fifteen-resource destroy diff on every pull request is TF-12's failure in the place this project
  treats as its strongest signal, and the only way to make it truthful is dev's live ACS
  credential in GitHub secrets. Retired rather than fixed; the reasoning is in
  [terraform/README.md](terraform/README.md). **`tf.sh apply` refuses a stale
  `app_image_tag`**, which is the other half of that variable being required at all: a mutable tag
  rolls no revision, and a *correct* tag nobody moves deploys a commit nobody has looked at. The
  second is what happened - the pin sat on phase 01's commit while ~25 PRs merged, and the apply
  that was meant to ship the broker relay rolled its Terraform onto week-old code with `plan`
  reading *No changes* afterwards, correctly. The check runs before `terraform init` (a refusal
  should not cost a round trip to the backend), compares against the local `origin/main` without
  fetching, and refuses a tag that is not a 40-character SHA, one that is not an ancestor of the
  tip, and one behind it. `--allow-stale-image` is the acknowledgement, for the two cases that are
  decisions rather than oversights: CD has not finished pushing the tip's image yet, or the
  rollback is deliberate. `PinnedImageTagGuardTest` pins the couplings nothing else can see - that
  it runs on `apply` only, that it runs before `init`, and that the flag is stripped before
  Terraform sees an argument it does not know. The Postgres JDBC URL uses
  `sslmode=verify-full`, not `require` — `require` encrypts without authenticating the server. **It
  carries `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`, and that half is load-bearing**:
  pgjdbc's default for a verifying mode is `LibPQFactory`, which follows libpq's convention and
  reads `~/.postgresql/root.crt` off disk. It never consults the JDK trust store and does not fall
  back to it — it refuses to connect, with `Could not open SSL root certificate file`. That is not
  hypothetical: `verify-full` sat on `main` for six revisions and the first revision ever to use it
  reached `ActivationFailed` on exactly that. With the factory set, the original reasoning holds and
  is why no cert is bundled: the roots Azure presents ("DigiCert Global Root G2", "Microsoft RSA
  Root Certificate Authority 2017") are already in the JDK trust store.
- [terraform/](terraform/) — Azure deployment (Container Apps behind a VNet, Postgres Flexible Server, Key Vault, a Storage account for attachments, Log Analytics) split into `modules/{vnet,key_vault,postgres,storage,api_app,web_app}`. **Two Container Apps in one environment**: `kanban-web-<env>` (`modules/web_app`, nginx, the only external ingress) and `kanban-api-<env>` (`modules/api_app`, the jar, `external_enabled = false`). Both land in the same `snet-backend`, so the split adds no subnet, no NSG rule and no private endpoint; the blob and Key Vault grants stay with the API identity alone, and the edge gets a second identity granted `Key Vault Secrets User` on the **`GHCR-TOKEN` secret alone** rather than on the vault — a vault-scoped grant would let the nginx container read the Postgres password. The edge proxies to `https://kanban-api-<env>.internal.<env-domain>`: internal ingress still terminates TLS and answers plain HTTP with a 301, and the certificate it presents carries `*.internal.<env-domain>` and verifies against the stock CA bundle, so the hop is authenticated rather than merely allowed by `allow_insecure_connections`. The two modules compose that name independently so neither depends on the other's resources, and a root-level `check` block asserts they still agree — the failure otherwise is a green apply and a 502 on every API call. Renaming the module is a state move, not a rebuild: `terraform state mv module.container_app module.api_app` before the first apply, or `random_password.jwt_secret_key` is regenerated and every signed-in user is signed out. The VNet is four subnets: the Container Apps infrastructure subnet, the delegated Postgres subnet, and one private-endpoint subnet each for Key Vault and blob — separate so each service's reachability is its own NSG rule rather than one rule covering both. The blob role assignment lives in `api_app` rather than `storage`, because the identity it is granted to is created there and the storage module would otherwise have to depend on the module that depends on it. Environments are separated by distinct backend state keys rather than workspaces: `terraform init -reconfigure -backend-config="key=env/dev/terraform.tfstate"`, then `terraform plan -var-file "dev.tfvars"`. See [terraform/README.md](terraform/README.md) for the Azure RBAC prerequisites — it is the authoritative doc for infra work.

### i18n

Nine locales live in [frontend/public/locales/](frontend/public/locales/) (`ar`, `de`, `en`, `es`, `fr`, `it`, `ja`, `pl`, `ru`), loaded at runtime by `i18next-http-backend` with browser language detection. User-facing strings — including every toast raised from `KanbanContext` — go through `t()` with a translation key, so a new message means adding the key to all locale files.

**`i18n.test.js` checks that claim two ways, and the second one is new.** Key parity across the
nine bundles has held all along; what it never looked at was the screen. It read source for one
pattern — a literal passed to `toast.*` — and never a JSX text node or a `title` / `aria-label` /
`placeholder` / `alt` attribute, which is exactly where the leaks were: three Polish strings in a
card popover that every non-Polish reader saw, a `title="row.delete"` rendering the translation
key itself, and around thirty English literals across the task panel, the bench and the demo
banner. Fifty-seven in all, against an artifact that had spotted fourteen by eye.

The widened half **parses the JSX with Babel's own parser rather than matching it with a regex**,
because the shapes that matter cannot be told apart by one: `{isOpen ? 'Hide' : 'Show'}` is prose
and `className={isOpen ? 'open' : ''}` is not, and both are a string literal inside a conditional.
The parser makes the distinction cheap — an expression that is a **child** of an element is on
screen, an expression that is an **attribute value** mostly is not — and it is why
`@babel/parser` is now a declared devDependency rather than something reached through hoisting.
It also reads `a || b` and template literals, which is how it caught a row of dead
`t('key') || 'Polish fallback'` expressions: `t()` never returns a falsy value, so the fallback
was unreachable code whose only effect was to hide a missing key from review.

Most of the Polish leaks needed no new wording at all — `taskActions.description`,
`taskActions.noSubtasks`, `bench.title` and the rest were already there, in all nine bundles,
beside the hardcoded string. That is the argument for the guard rather than for the fixes: the
keys were never the hard part.

**Mail is the tenth bundle set, and it is on the server (`V11`).** The client picks its own language;
the two moments mail is composed have no client to ask — a verification code is written by a route
whose browser may never be seen again, and an overdue notice by a scheduler with no request at all —
so the account carries a `locale` column and `MailTemplates` reads
`backend/src/main/resources/mail/messages*.properties` through a `ResourceBundleMessageSource`.
There are four messages: a verification code, a reset code, an overdue task and a board invitation.
`messages.properties` **is** the English one, which is why there is no `messages_en`, and
`fallbackToSystemLocale` is off so an unmatched language falls back to that file rather than to
whatever locale the JVM was started in.

`SupportedLocales` is the single list of what counts as a language here, and
`SupportedLocalesMatchClientTest` fails the build when it stops matching the directories under
`frontend/public/locales` — **adding a tenth language means a client bundle, a `messages_<tag>.properties`
and an entry in `SupportedLocales.TAGS`**, and nothing but that test connects the three. Tags are
stored as the language subtag only (`de-AT` is `de`), because no bundle here has a regional variant.
Signup guesses — the client sends `i18n.language`, the controller falls back to `Accept-Language`,
and an unrecognised tag becomes English without complaint. `PATCH /api/users/{id}` with a `locale`
does not guess: an unsupported tag is `400 UNSUPPORTED_LOCALE`, because that one is a choice rather
than a header. The language switcher writes it whenever somebody is signed in, so the mail follows
the screen without a second control to find.

One trap in the bundles, and it is invisible to every compiler involved: Spring runs a message
through `MessageFormat` only when it is given arguments, so a lone apostrophe is harmless in a
message with no `{0}` and **swallows the rest of the pattern** in one that has them.
`MailTemplatesTest` renders all four messages in all nine locales and fails on a surviving brace,
which is what that mistake produces.
