ALTER TABLE users ADD COLUMN verification_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN password_reset_attempts integer NOT NULL DEFAULT 0;
