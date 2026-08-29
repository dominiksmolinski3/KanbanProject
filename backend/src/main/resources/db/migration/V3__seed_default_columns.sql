--
-- The default workflow stages, positioned in the order they are meant to read.
--
-- These used to live in db.sql, which docker-compose mounted as a Postgres init script. That only
-- ever ran against an empty volume and only in local development, so the stages a fresh deployment
-- came up with depended on how it had been started. They are part of the schema's starting state,
-- so they belong with the schema.
--
-- Guarded on the table being empty rather than on the individual names: re-seeding a board someone
-- has since renamed or reordered would be worse than not seeding it.
--
-- There are deliberately no seed users. The two that used to sit in db.sql carried plaintext
-- passwords and no `enabled` value, so they could never pass the verification flow. Accounts come
-- from POST /api/auth/signup.
--

INSERT INTO columns (name, position, wip_limit)
SELECT * FROM (VALUES
    ('New Issues',      1, 0),
    ('Icebox',          2, 0),
    ('Product Backlog', 3, 0),
    ('Sprint Backlog',  4, 10),
    ('In Progress',     5, 5),
    ('QA/Review',       6, 0),
    ('Done',            7, 0),
    ('Closed',          8, 0)
) AS seed(name, position, wip_limit)
WHERE NOT EXISTS (SELECT 1 FROM columns);
