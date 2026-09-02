--
-- What a refresh-token row has to say for a person to recognise it.
--
-- V7 made the session a row so the server could end one. It did not make the session something the
-- person holding it could see: a live row was a digest, two timestamps and a user id, which answers
-- "how many sessions are there" and nothing at all about which is the laptop at work and which is
-- the phone that was lost on a train. Revocation nobody can aim is only half a control.
--
-- ip_address and user_agent are stamped on every issue - the login that starts a chain and every
-- rotation after it - so they describe where the session is now rather than where it began. A
-- device that moves between networks is the normal case, not a suspicious one; a user agent that
-- changes under the same chain is the notable one, and keeping the latest is what makes that
-- visible. Both are nullable: rows written before this migration have neither, and a request may
-- legitimately arrive with no User-Agent header at all.
--
-- 45 characters is a full IPv6 address in its longest textual form (an IPv4-mapped one). The user
-- agent is truncated to 255 rather than stored whole - it is a label for a human reading a list,
-- not a forensic record, and it is attacker-controlled free text on an unauthenticated route.
--
-- chain_started_at is the one that could not be derived. Rotation writes a new row every time, so
-- issued_at on the live row is the last renewal - a useful "last seen", and useless as "signed in
-- since", which is what somebody auditing their own sessions is actually looking for. Like
-- absolute_expires_at in V8 it is stamped once at login and copied forward unchanged, so the pair
-- of them describe one session across all of its rotations.
--

ALTER TABLE refresh_tokens ADD COLUMN ip_address varchar(45);
ALTER TABLE refresh_tokens ADD COLUMN user_agent varchar(255);
ALTER TABLE refresh_tokens ADD COLUMN chain_started_at timestamp(6) WITH TIME ZONE;

--
-- issued_at is the honest backfill: for a row that has never rotated it is exactly right, and for
-- one that has it is the earliest instant this schema ever recorded about that chain. Guessing
-- further back would be inventing a login nobody wrote down.
--
UPDATE refresh_tokens SET chain_started_at = issued_at WHERE chain_started_at IS NULL;

--
-- Only once every row has one, for the V5 reason: the other order fails against a database that
-- already has sessions in it.
--
ALTER TABLE refresh_tokens ALTER COLUMN chain_started_at SET NOT NULL;
