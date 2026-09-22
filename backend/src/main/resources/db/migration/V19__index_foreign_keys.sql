--
-- An index for every foreign key that did not have one.
--
-- Postgres, unlike MySQL, creates no index for a foreign key constraint. It indexes the side it
-- points *at* - that is the primary key it references - and leaves the referencing column bare, so
-- every "find the rows pointing at this one" is a sequential scan and every delete or update of a
-- referenced row scans each child table to check the constraint. Nothing about that is visible on
-- a seeded board, which is the argument for doing it now rather than after there is data.
--
-- The nine indexes the earlier migrations declared were each added for a query somebody had in
-- hand - the outbox due-scan, the activity feed, the attachment listing, the chat threads, and
-- board_id on the three board-scoped tables. These are the other set: the ones nobody had a reason
-- to add yet, read out of the foreign keys themselves rather than out of a profiler.
--
-- Four of them are on paths this application takes constantly:
--
--   * board_members (user_id) - the composite primary key is (board_id, user_id), which serves
--     "who is on this board" and cannot serve "which boards is this account on". The second is the
--     board switcher and every membership check BoardService makes, which is to say every request.
--   * task_labels (task_id) - an @ElementCollection with no key of any kind, read on every task.
--   * user_task (user_id) - the same composite-key asymmetry, and the query behind
--     UserService.checkWipStatus, the one WIP limit with a server-side check.
--   * task_column_history (column_id) - V17 taught ColumnService.deleteColumn to detach these rows
--     with UPDATE ... WHERE column_id = ?, against a column with no index at all.
--
-- Plain CREATE INDEX rather than CONCURRENTLY: Flyway runs a migration in one transaction and
-- CONCURRENTLY cannot run inside one, so the concurrent form would mean marking this migration
-- non-transactional to buy a lock this deployment's table sizes do not make expensive.
--
-- IF NOT EXISTS throughout, because an environment baselined at V1 from ddl-auto=update may
-- already carry some of these under Hibernate's own names.
--

-- Membership, ownership. The hot pair is board_members (user_id); the two owner columns are read
-- by BoardService and FileService on every check they make.
CREATE INDEX IF NOT EXISTS ix_board_members_user ON board_members (user_id);
CREATE INDEX IF NOT EXISTS ix_boards_owner       ON boards (owner_id);
CREATE INDEX IF NOT EXISTS ix_files_owner        ON files (owner_id);

-- The board's contents. task (column_id) and (row_id) are the cell a reorder names and the pair
-- the BOARD_MISMATCH check compares; (parent_task_id) is canTaskBeCompleted and the completion
-- cascade, which walks children by definition.
CREATE INDEX IF NOT EXISTS ix_task_column      ON task (column_id);
CREATE INDEX IF NOT EXISTS ix_task_row         ON task (row_id);
CREATE INDEX IF NOT EXISTS ix_task_parent      ON task (parent_task_id);
CREATE INDEX IF NOT EXISTS ix_subtasks_task    ON subtasks (task_id);
CREATE INDEX IF NOT EXISTS ix_task_labels_task ON task_labels (task_id);
CREATE INDEX IF NOT EXISTS ix_user_task_user   ON user_task (user_id);

-- The two history tables. task_column_history is read forwards by the task panel's time-per-column
-- fold and written sideways by V17's detach; task_activity's board_id is already indexed for the
-- feed, so these two are the columns the feed does not order by.
CREATE INDEX IF NOT EXISTS ix_task_column_history_task   ON task_column_history (task_id);
CREATE INDEX IF NOT EXISTS ix_task_column_history_column ON task_column_history (column_id);
CREATE INDEX IF NOT EXISTS ix_task_activity_task         ON task_activity (task_id);
CREATE INDEX IF NOT EXISTS ix_task_activity_actor        ON task_activity (actor_id);

-- Attachments and invitations. task_attachments (uploaded_by) is ON DELETE SET NULL, so closing an
-- account scans every attachment row in the deployment to find the ones to blank; board_invitations
-- (board_id) is what BoardService.deleteBoard clears by hand, and the unique index above it is
-- partial (WHERE status = 'PENDING') so it cannot serve a lookup that ignores status.
CREATE INDEX IF NOT EXISTS ix_task_attachments_uploaded_by ON task_attachments (uploaded_by);
CREATE INDEX IF NOT EXISTS ix_board_invitations_board      ON board_invitations (board_id);
CREATE INDEX IF NOT EXISTS ix_board_invitations_invited_by ON board_invitations (invited_by_id);
