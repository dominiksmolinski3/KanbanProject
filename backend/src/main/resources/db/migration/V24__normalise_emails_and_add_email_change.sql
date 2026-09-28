-- A row whose normalised address would collide with another row is left alone: the unique constraint would refuse it.
UPDATE users u
SET email = lower(trim(u.email))
WHERE u.email <> lower(trim(u.email))
  AND NOT EXISTS (
      SELECT 1 FROM users other
      WHERE other.id <> u.id
        AND lower(trim(other.email)) = lower(trim(u.email)));

ALTER TABLE users ADD COLUMN pending_email varchar(255);
ALTER TABLE users ADD COLUMN email_change_code varchar(255);
ALTER TABLE users ADD COLUMN email_change_expiration timestamp(6);
ALTER TABLE users ADD COLUMN email_change_attempts integer NOT NULL DEFAULT 0;
