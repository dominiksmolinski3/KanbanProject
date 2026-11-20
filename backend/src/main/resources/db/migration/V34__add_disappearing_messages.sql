ALTER TABLE conversations
    ADD COLUMN disappear_after_seconds integer;

ALTER TABLE conversations
    ADD CONSTRAINT ck_conversations_disappear_after CHECK (disappear_after_seconds IS NULL OR disappear_after_seconds > 0);
