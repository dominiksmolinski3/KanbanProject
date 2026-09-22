--
-- The commonest access level this schema never had: a stakeholder who should see a board and not
-- move anything on it. Two levels - owner (boards.owner_id) and member - stop meaning "no access or
-- full write" and start meaning "how much write", by adding a role to the membership itself rather
-- than a third level of ownership. Ownership is unrelated and stays exactly as it was.
--
-- Every row already in board_members predates the concept of a viewer and becomes 'member', which
-- is the write access those accounts already had - the same backfill rule V5 used when boards
-- themselves were introduced: leave an existing installation working exactly as it did.
--
alter table board_members
    add column role varchar(16) not null default 'MEMBER';

-- An invitation now has to be able to offer either role, so the role travels with the offer rather
-- than being decided only at acceptance. Existing/omitted invitations default to 'MEMBER', matching
-- the invite-creation UI that predates this column until it is updated to offer a choice.
alter table board_invitations
    add column role varchar(16) not null default 'MEMBER';
