--
-- An absolute ceiling on the refresh-token chain.
--
-- The refresh token from V7 is a sliding window: every rotation issues a replacement expiring the
-- window from now, so an account in regular use is renewed indefinitely and its expiry never
-- arrives. That is the intended behaviour for the person holding it and the wrong behaviour for a
-- token someone stole. A thief who refreshes before the real client does holds a chain that rotates
-- cleanly - the reuse check never fires, because the real client's token is the one that goes
-- stale - and the sliding expiry moves out from under any deadline meant to catch it.
--
-- absolute_expires_at is the deadline the window cannot slide past. It is stamped once, at the
-- login that starts the chain, and copied forward unchanged on every rotation; the effective expiry
-- a check reads is the earlier of it and the sliding window. When it passes, the next rotation is
-- refused for the same reason an expired token is, and the account signs in again.
--
-- Existing rows were issued under the unbounded rule. The honest backfill is expires_at: it is
-- where they already point, so it stops them sliding any further without cutting short the life
-- they already have.
--

ALTER TABLE refresh_tokens ADD COLUMN absolute_expires_at timestamp(6) WITH TIME ZONE;

UPDATE refresh_tokens SET absolute_expires_at = expires_at WHERE absolute_expires_at IS NULL;

--
-- Only now, once every row has one, does the column become mandatory - the V5 order, for the V5
-- reason: the other way round fails against a database that already has tokens in it.
--
ALTER TABLE refresh_tokens ALTER COLUMN absolute_expires_at SET NOT NULL;
