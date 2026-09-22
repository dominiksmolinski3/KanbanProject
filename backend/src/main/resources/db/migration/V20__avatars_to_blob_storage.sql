--
-- Avatars move out of Postgres, the same way task attachments already did in V12: the bytes go to
-- Azure Blob Storage and these columns on `users` are what name them. Before this, an avatar was a
-- row in `files` - a @Lob bytea column - which meant every uploaded avatar was carried by the
-- database's storage, its backups and its point-in-time restore window, for data no query ever
-- looked inside. `files` existed for nothing else: FileController's /api/files route was declared
-- in the client's endpoint list and called by nothing (ClientRoutesExistTest's own Javadoc already
-- named it "owned and unused"), so the whole table goes with it rather than only the avatar half.
--
-- One user, one avatar, so this is four columns on `users` rather than a second table with a
-- foreign key back - the shape task_attachments needed because a task holds many, a user holds at
-- most one. avatar_blob_name is opaque (avatars/<userId>/<uuid>), the same reasoning
-- task_attachments.blob_name is: nothing a person typed belongs in a URL path on a shared storage
-- account. size_bytes and content_type are stored rather than asked of the storage account on every
-- read, for the same reason task_attachments stores them - Content-Length has to come from
-- somewhere that isn't an extra Azure call.
--
-- No backfill. This repository has one deployed environment (dev, see CLAUDE.md) and it is
-- disposable for schema purposes - the same call V18 made for chat_messages.room_id, which it left
-- null rather than inventing an unmapped mapping. Nothing here can turn a `files` bytea into an
-- Azure blob without a running application to do the copy, so any avatar already set needs a
-- re-upload; that is a one-time, low-cost inconvenience against a fresh dev database, not a data
-- loss anyone downstream depends on this migration to prevent.
--
-- The FK name (FKda0fl66rh9qsoty4s66xk29m6) is Hibernate's own generated hash for
-- users.avatar_id -> files, reproduced exactly as V1's comment says migrations reference it -
-- an environment baselined from ddl-auto=update carries the identical name.
--

ALTER TABLE users ADD COLUMN avatar_blob_name    varchar(200);
ALTER TABLE users ADD COLUMN avatar_content_type varchar(255);
ALTER TABLE users ADD COLUMN avatar_size_bytes   bigint;
ALTER TABLE users ADD COLUMN avatar_uploaded_at  timestamp(6) WITH TIME ZONE;

ALTER TABLE users DROP CONSTRAINT IF EXISTS FKda0fl66rh9qsoty4s66xk29m6;
ALTER TABLE users DROP COLUMN IF EXISTS avatar_id;

DROP TABLE IF EXISTS files;
