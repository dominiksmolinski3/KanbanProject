--
-- The migration the audit said had nowhere to live.
--
-- `User.enabled` is a primitive declared `nullable = false`, but declaring it does nothing for a
-- row that is already null - and rows like that exist, because the seed users that used to ship in
-- db.sql carried no `enabled` value at all. Every read of one unboxed null into an NPE in the
-- middle of authenticating.
--
-- Hibernate's ddl-auto=update would never have written either statement: it adds columns and
-- never tightens or backfills one. On a database created fresh from V1 both are no-ops, which is
-- what makes them safe to run everywhere.
--

UPDATE users SET enabled = FALSE WHERE enabled IS NULL;

ALTER TABLE users ALTER COLUMN enabled SET NOT NULL;
