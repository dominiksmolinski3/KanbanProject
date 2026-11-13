CREATE INDEX ix_task_deadline ON task (deadline) WHERE deadline IS NOT NULL;
