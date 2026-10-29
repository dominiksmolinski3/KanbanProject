-- The API container runs in UTC, so every value here was written as a UTC wall-clock time.
ALTER TABLE task_column_history ALTER COLUMN changed_at TYPE timestamp(6) WITH TIME ZONE USING changed_at AT TIME ZONE 'UTC';
ALTER TABLE task_activity ALTER COLUMN occurred_at TYPE timestamp(6) WITH TIME ZONE USING occurred_at AT TIME ZONE 'UTC';
ALTER TABLE chat_messages ALTER COLUMN "timestamp" TYPE timestamp(6) WITH TIME ZONE USING "timestamp" AT TIME ZONE 'UTC';
ALTER TABLE board_invitations
    ALTER COLUMN created_at TYPE timestamp(6) WITH TIME ZONE USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN responded_at TYPE timestamp(6) WITH TIME ZONE USING responded_at AT TIME ZONE 'UTC';
ALTER TABLE boards ALTER COLUMN created_at TYPE timestamp(6) WITH TIME ZONE USING created_at AT TIME ZONE 'UTC';
ALTER TABLE users
    ALTER COLUMN verification_expiration TYPE timestamp(6) WITH TIME ZONE USING verification_expiration AT TIME ZONE 'UTC',
    ALTER COLUMN password_reset_expiration TYPE timestamp(6) WITH TIME ZONE USING password_reset_expiration AT TIME ZONE 'UTC',
    ALTER COLUMN email_change_expiration TYPE timestamp(6) WITH TIME ZONE USING email_change_expiration AT TIME ZONE 'UTC';
