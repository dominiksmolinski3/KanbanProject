--
-- A board's own definition of when work starts and when it is done (FLOW-02).
--
-- The flow screen (FEAT-07) measured cycle time up to the board's last column unless somebody picked
-- another, and the pick lived in that one browser tab. The default board ends Done, Closed, so a team
-- that stops at Done opened the screen on "0 finished" every time, and two people looking at the same
-- board could be reading two different definitions of done without knowing it. Stored here, it is one
-- definition per board, set by the owner the way the name is, and the screen reads it like everyone
-- else.
--
-- Both are nullable, and null keeps FEAT-07's rule: done is the last column, and the start is a card's
-- arrival on the board, which makes the number a lead time. Existing boards are left null rather than
-- backfilled from column names - a column called Done is a guess about what a team means by it, and
-- this project does not guess where the history cannot say.
--
-- ON DELETE SET NULL, unlike most keys here, because the column is the board's setting rather than
-- the board's content: deleting the column somebody chose as done should put the board back on the
-- default, not refuse the delete or take anything else with it. ColumnService clears the reference
-- in Java as well, so a board already loaded in the same transaction cannot write the old id back.
--
ALTER TABLE boards ADD COLUMN flow_start_column_id integer;
ALTER TABLE boards ADD COLUMN flow_done_column_id integer;

ALTER TABLE boards
    ADD CONSTRAINT fk_boards_flow_start_column FOREIGN KEY (flow_start_column_id)
        REFERENCES columns (id) ON DELETE SET NULL;

ALTER TABLE boards
    ADD CONSTRAINT fk_boards_flow_done_column FOREIGN KEY (flow_done_column_id)
        REFERENCES columns (id) ON DELETE SET NULL;

-- Postgres indexes neither side of a foreign key it does not own; without these, every column delete
-- scans boards to find what to null (ForeignKeysAreIndexedTest).
CREATE INDEX ix_boards_flow_start_column ON boards (flow_start_column_id);
CREATE INDEX ix_boards_flow_done_column ON boards (flow_done_column_id);
