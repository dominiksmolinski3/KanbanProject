# KanbanProject API

The Spring Boot 4.1 API behind the board and the encrypted messenger: REST under `/api`, STOMP at
`/ws` (the client opens the plain WebSocket at `/ws/websocket`), and the OpenAPI document at
`/v3/api-docs`. The code targets Java 21; CI and the Docker image build on
Temurin JDK 25.

## Run it

It needs PostgreSQL, Redis and a RabbitMQ with the STOMP plugin. The root README's
[Running Locally](../README.md#running-locally) section has the two `docker run` lines and the
variables. Copy `.env.example` to `.env` in this directory and fill it in, then:

```bash
./mvnw spring-boot:run        # on :8080 (mvnw.cmd on Windows)
```

Flyway migrates the database on start. A broker on another host needs `STOMP_RELAY_USERNAME` and
`STOMP_RELAY_PASSWORD`: RabbitMQ accepts its `guest` user from loopback only, and the API refuses
to start with that combination rather than failing on the first board update.

## Test it

```bash
./mvnw test                                   # unit tests and build guards, no database needed
./mvnw test -Dtest=PublicChainPathsTest       # one class
./mvnw verify                                 # what CI runs: tests plus the JaCoCo floors
./mvnw clean test jacoco:report               # coverage report in target/site/jacoco/index.html
```

The Redis integration tests (`ApiRateLimiterIntegrationTest` and its neighbours in
`config/security/ratelimit`) talk to a real Redis on `localhost:6379`. Point them elsewhere with
`REDIS_HOST` and `REDIS_PORT` if another Redis already holds that port.

The tests under `database/` migrate a throwaway schema in a real Postgres. The root README's
[Testing](../README.md#backend-1) section has the one-line container and the variables they read.

## Find your way around

- Packages are by feature (`board/`, `task/`, `user/`, `layout/`, `chat/`, `messaging/`), each
  holding its entity, controller, service, repository, mapper and DTO records. Beside them are
  `mail/` and the cross-cutting `config/`, `exception/` and `storage/`.
- `messaging/` is the server half of the encrypted messenger: keys and devices, conversations and
  board channels, the opaque message and file store, device linking and push notifications. It
  never sees a plaintext message. `chat/` only serves the read-only history of the old plaintext
  chat until it is pruned.
- Migrations are in `src/main/resources/db/migration`. Never edit one that is on `main`.
- Many rules are enforced by database-free build guards rather than by review, for example
  `BoardScopedRoutesTest`, `RequestBodyBindingTest` and `WriteAccessCoverageTest`. A failing guard
  explains what it protects in its assertion message.

[docs/architecture.md](../docs/architecture.md) is the detailed architecture reference, and
[CONTRIBUTING.md](../CONTRIBUTING.md) lists the traps.
