-- What happened on a board, and who did it.
--
-- V15 follows V14 (board_invitations), which is this branch's parent in the stack. That ordering
-- is not decoration: Flyway rejects a migration that turns up below the version already applied,
-- so a V15 deployed before V14 exists makes the *next* deploy fail rather than this one - a green
-- build, a healthy revision, and a container that refuses a week later. Stacking the branch is
-- what makes the order impossible to get wrong; two independent PRs would not have.
--
-- task_id is nullable and the title is copied onto the row, so a deleted task leaves its history
-- readable instead of taking it away - "X deleted <task>" is the one entry that must survive the
-- thing it describes. actor_id is nullable for the same reason applied to accounts, and the two
-- copied names are what task_column_history.column_name already does: an event log records what
-- was true when it happened, not what is true now.
create table task_activity
(
    id          serial primary key,
    board_id    integer      not null references boards (id),
    task_id     integer references task (id),
    task_title  varchar(255) not null,
    actor_id    integer references users (id),
    actor_name  varchar(255),
    type        varchar(24)  not null,
    detail      varchar(255),
    occurred_at timestamp    not null
);

-- The feed is "this board, newest first, paged". id is in the key because occurred_at ties on a
-- batch written in one transaction, and any order with ties makes paging skip and repeat rows.
create index ix_task_activity_board on task_activity (board_id, occurred_at desc, id desc);
