--
-- Optimistic locking on the four things a board edits concurrently.
--
-- A board is edited by every member at once, and the client reorders a cell by sending one
-- position PATCH per card in it. Two people dragging in the same column therefore race by
-- construction, and until now the second write won silently. A @Version column turns that into a
-- lost UPDATE ... WHERE version = ?, which Hibernate raises and the API answers as 409 rather than
-- discarding one person's move without telling them.
--
-- NOT NULL DEFAULT 0 so every row that already exists is versioned from this migration forward;
-- the default is only there for the backfill, since Hibernate sets the value itself on every
-- insert and bumps it on every update.
--

ALTER TABLE task     ADD COLUMN version integer NOT NULL DEFAULT 0;
ALTER TABLE columns  ADD COLUMN version integer NOT NULL DEFAULT 0;
ALTER TABLE rows     ADD COLUMN version integer NOT NULL DEFAULT 0;
ALTER TABLE subtasks ADD COLUMN version integer NOT NULL DEFAULT 0;
