-- Membership becomes something a person accepts rather than something somebody assigns them.
--
-- The row is keyed on an email address rather than on a user id, and that is the whole design:
-- an invitation has to be able to name somebody who has no account here yet, or the "invite a
-- new person" path would have to create an account on their behalf - which lets any account
-- squat any address and block the real owner from ever signing up with it.
--
-- Addresses are stored lower-cased by the application, so the unique index below is a plain one
-- rather than an expression index. Only one invitation per (board, address) may be pending at a
-- time; the accepted, declined and revoked rows stay, so re-inviting after a decline is a new
-- row rather than a rewritten one and the board keeps its record of what was asked.
create table board_invitations
(
    id            serial primary key,
    board_id      integer      not null references boards (id),
    email         varchar(255) not null,
    invited_by_id integer references users (id),
    status        varchar(16)  not null,
    created_at    timestamp    not null,
    responded_at  timestamp
);

create unique index ux_board_invitations_pending
    on board_invitations (board_id, email)
    where status = 'PENDING';

-- The invitee's own listing: "which boards am I being asked to join", answered by address.
create index ix_board_invitations_email on board_invitations (email);
