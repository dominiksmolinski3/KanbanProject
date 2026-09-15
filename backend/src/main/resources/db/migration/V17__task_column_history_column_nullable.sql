-- task_column_history.column_id was `not null` with no cascade, so deleting a column that had ever
-- held a task - not just one holding a task right now - failed on this foreign key the moment the
-- task had since moved elsewhere. column_name is already a copy on the row for exactly the reason
-- task_activity's task_id is nullable: an event log records what was true when it happened, and the
-- row is meant to outlive its subject. The column reference just never got the same treatment.
alter table task_column_history alter column column_id drop not null;
