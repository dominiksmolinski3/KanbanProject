--
-- Mounted by docker-compose as a Postgres init script, so it runs once, against an empty volume.
-- It is not a migration system: Hibernate owns the schema at runtime (ddl-auto=update) and will
-- add whatever the running jar declares that is missing here.
--

CREATE TABLE columns (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    position INTEGER,
    wip_limit INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE tasks (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    position INTEGER,
    column_id INTEGER NOT NULL,
    FOREIGN KEY (column_id) REFERENCES columns(id) ON DELETE CASCADE
);

--
-- A starting set of workflow stages, positioned in the order they are meant to read.
--
-- There are deliberately no seed users. The two that used to sit here carried plaintext passwords
-- and no `enabled` value, so they could never pass the verification flow, and every read of one
-- unboxed a null `enabled` into an NPE in the middle of authenticating. Accounts come from
-- POST /api/auth/signup.
--

BEGIN;

INSERT INTO columns (name, position, wip_limit) VALUES
    ('New Issues',      1, 0),
    ('Icebox',          2, 0),
    ('Product Backlog', 3, 0),
    ('Sprint Backlog',  4, 10),
    ('In Progress',     5, 5),
    ('QA/Review',       6, 0),
    ('Done',            7, 0),
    ('Closed',          8, 0);

COMMIT;
