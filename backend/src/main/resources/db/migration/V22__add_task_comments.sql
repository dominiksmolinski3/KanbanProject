--
-- What people say about one card (FEAT-06).
--
-- Until now the only place to say anything about a card was chat, which is scoped to the board the
-- card is on but is still the whole board's conversation. A comment is the card's own, and it is
-- scoped the way an attachment is: through the task, with no board column of its own, so a caller
-- may read a comment exactly when they may read the card - the task decides, and nothing here can
-- disagree with it.
--
-- task_id has no ON DELETE CASCADE, for the reason task_attachments has none: this project removes
-- what hangs off a task by a service call (TaskService.deleteTask, BoardService.deleteBoard) rather
-- than by a rule buried in the schema, so every removal is one somebody can read in Java.
--
-- author_id is nullable and ON DELETE SET NULL, like task_attachments.uploaded_by: an account can go
-- while the card it commented on is still in use, and a comment is the card's record, not the
-- account's. The body is text a person typed and is stored as typed - nothing here is a sentence the
-- server composed, which is the activity feed's rule seen from the other side.
--
-- edited_at is null until somebody edits; a comment that says it was edited is the honest version of
-- letting its author rewrite it.
--
CREATE TABLE task_comments
(
    id         bigserial                   PRIMARY KEY,
    task_id    integer                     NOT NULL,
    author_id  integer,
    body       varchar(2000)               NOT NULL,
    created_at timestamp(6) WITH TIME ZONE NOT NULL,
    edited_at  timestamp(6) WITH TIME ZONE
);

ALTER TABLE task_comments
    ADD CONSTRAINT fk_task_comments_task FOREIGN KEY (task_id) REFERENCES task (id);

ALTER TABLE task_comments
    ADD CONSTRAINT fk_task_comments_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL;

--
-- The thread is "this card, newest first, paged", with id in the key for the reason task_activity's
-- index has it: created_at can tie, and any order with ties makes paging skip and repeat rows.
-- It also covers task_id for the foreign key, since it leads with it.
--
CREATE INDEX idx_task_comments_task ON task_comments (task_id, created_at DESC, id DESC);

--
-- Postgres indexes no foreign key on the referencing side (V19). Without this, the SET NULL an
-- account deletion triggers scans every comment in the deployment; ForeignKeysAreIndexedTest fails
-- the build if it is missing.
--
CREATE INDEX idx_task_comments_author ON task_comments (author_id);
