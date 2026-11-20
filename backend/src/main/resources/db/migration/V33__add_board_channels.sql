ALTER TABLE conversations
    ADD COLUMN board_id integer;

ALTER TABLE conversations
    ADD CONSTRAINT uq_conversations_board UNIQUE (board_id);
ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_board FOREIGN KEY (board_id) REFERENCES boards (id) ON DELETE CASCADE;

INSERT INTO conversations (kind, board_id, group_id, epoch, next_seq, needs_commit, history_visibility, who_can_add,
                           created_at, last_activity_at)
SELECT 'BOARD', board.id, decode(replace(gen_random_uuid()::text, '-', ''), 'hex'), 0, 1, false, 'JOINED', 'ADMINS',
       now(), now()
FROM boards board;

INSERT INTO conversation_members (conversation_id, user_id, role, state, joined_seq, delivered_seq, read_seq, muted,
                                  pinned, archived, joined_at)
SELECT conversation.id, member.user_id,
       CASE WHEN member.user_id = board.owner_id THEN 'ADMIN'
            WHEN member.role = 'VIEWER' THEN 'VIEWER'
            ELSE 'MEMBER' END,
       'ACTIVE', 1, 0, 0, false, false, false, now()
FROM conversations conversation
         JOIN boards board ON board.id = conversation.board_id
         JOIN board_members member ON member.board_id = board.id;

INSERT INTO conversation_members (conversation_id, user_id, role, state, joined_seq, delivered_seq, read_seq, muted,
                                  pinned, archived, joined_at)
SELECT conversation.id, board.owner_id, 'ADMIN', 'ACTIVE', 1, 0, 0, false, false, false, now()
FROM conversations conversation
         JOIN boards board ON board.id = conversation.board_id
WHERE board.owner_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM board_members member
                  WHERE member.board_id = board.id AND member.user_id = board.owner_id);
