-- Chat joins the tenancy model.
--
-- Until now a message named a free-text room_id the client supplied, and a message with none went
-- to a single global topic every signed-in account subscribed to on connect - so a member of one
-- board read the messages of every other board's members. A message belongs to a board now, which
-- is the same unit of access every other table here is scoped by, and the subscription check that
-- already guards /topic/boards.{id} covers /topic/boards.{id}.chat for free.
--
-- board_id is nullable because a direct message is not on a board: it is addressed to a peer, and
-- what scopes it is that the recipient shares a board with the sender. Exactly one of the two is
-- set on any row this application writes from now on.
--
-- The rows already in the table are left where they are rather than backfilled or deleted. A
-- room_id of 'general' is not a board id and no mapping exists that would make one up honestly;
-- those rows keep a null board_id, which means the new read routes never return them - which is
-- what they already were, since nothing has ever read this table. The retention sweep collects
-- them in time.
alter table chat_messages
    add column board_id integer references boards (id);

alter table chat_messages
    drop column room_id;

-- "This board, newest first, paged" - id in the key because timestamp ties, and any order with
-- ties makes paging skip and repeat rows. Same reasoning as ix_task_activity_board.
create index ix_chat_messages_board on chat_messages (board_id, timestamp desc, id desc);

-- A direct thread is read in both directions (a sent to b, b sent to a), and one composite index
-- can only lead on one of them, so there are two.
create index ix_chat_messages_sent on chat_messages (sender, recipient_id, timestamp desc, id desc);
create index ix_chat_messages_received on chat_messages (recipient_id, sender, timestamp desc, id desc);
