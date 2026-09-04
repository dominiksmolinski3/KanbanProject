--
-- Files attached to a task, with the bytes somewhere else.
--
-- This table is deliberately not the shape `files` has. That one stores an upload as a bytea in
-- Postgres, which means every attachment on every board is carried by the database's storage, its
-- backups, its point-in-time restore window and its restore time - for data no query ever looks
-- inside and no index could help with. Ten megabytes is the per-file limit, the server runs on
-- 32 GB of provisioned storage, and a team that attaches a design mock a day fills it in a year.
--
-- So the bytes go to Azure Blob Storage and this row is what names them. Nothing else knows the
-- mapping: blob_name is generated (tasks/<taskId>/<uuid>) and the file name a person typed is only
-- ever a column here. That split is why blob_name is UNIQUE - two rows pointing at one blob would
-- mean deleting either one destroys the other's file - and why there is no ON DELETE CASCADE from
-- task. A cascade would take the rows and leave every blob behind with nothing left that knows the
-- name to remove; TaskService.deleteTask calls the attachment service instead, which removes both.
--
-- uploaded_by is nullable and its foreign key is ON DELETE SET NULL. An account can be closed while
-- its uploads stay on a board that is still in use: the attachment belongs to the task, and losing
-- the name of who added it is much better than losing the file with the account.
--
-- size_bytes is stored rather than asked of the storage account, because the panel lists it and a
-- listing should not cost one Azure call per row.
--
CREATE TABLE task_attachments
(
    id           bigserial                   PRIMARY KEY,
    task_id      integer                     NOT NULL,
    blob_name    varchar(200)                NOT NULL,
    file_name    varchar(255)                NOT NULL,
    content_type varchar(255)                NOT NULL,
    size_bytes   bigint                      NOT NULL,
    uploaded_by  integer,
    uploaded_at  timestamp(6) WITH TIME ZONE NOT NULL
);

ALTER TABLE task_attachments
    ADD CONSTRAINT uq_task_attachments_blob_name UNIQUE (blob_name);

ALTER TABLE task_attachments
    ADD CONSTRAINT fk_task_attachments_task FOREIGN KEY (task_id) REFERENCES task (id);

ALTER TABLE task_attachments
    ADD CONSTRAINT fk_task_attachments_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL;

--
-- The only query the feature makes: one task's attachments, oldest first. Without it, opening a
-- task panel is a sequential scan over every attachment in the deployment.
--
CREATE INDEX idx_task_attachments_task ON task_attachments (task_id, uploaded_at, id);
