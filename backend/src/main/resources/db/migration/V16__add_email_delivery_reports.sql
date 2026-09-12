--
-- What happened to a message after Azure took it.
--
-- email_outbox has answered one question since V10: did the provider accept this? SENT means it
-- did. It has never been able to answer the question anybody actually asks, which is whether the
-- code reached the person waiting for it - acceptance is not delivery, and a hard bounce, a spam
-- rejection or a mistyped domain that got past validation all happen afterwards, out of band,
-- somewhere this application was not looking.
--
-- Azure does know. It publishes a delivery report per recipient, and the report names the message
-- by the id the send operation returned. So the join key has to be written down at the moment of
-- acceptance or the report has nothing to attach to: provider_message_id is that id, captured by
-- the relay from the operation Azure opens for each message.
--
--   provider_message_id  - Azure's id for the message. Null for a DROPPED row (no provider saw
--                          it), for rows queued before this migration, and for the rare send whose
--                          id could not be read back - none of which is a failure, only a row that
--                          cannot be matched.
--   delivery_status      - the provider's own word, stored verbatim: Delivered, Bounced, Failed,
--                          Quarantined, FilteredSpam, Suppressed. Not mapped to an enum of this
--                          application's invention, because the person reading this column is
--                          diagnosing a mail that did not arrive and the provider's vocabulary is
--                          what the provider's documentation and its own logs are written in. A
--                          value nobody here has seen before must land in this column rather than
--                          be refused: a provider that adds a status is not a reason to drop the
--                          report on the floor.
--   delivery_reported_at - when the provider says the attempt happened, not when we heard about
--                          it. Reports can arrive out of order, and this is what decides which of
--                          two reports about one message is the later one.
--   delivery_detail      - the provider's explanation, when it gives one. Truncated; it is a note
--                          for whoever is reading the table.
--
-- Nothing here is NOT NULL and nothing is backfilled, because there is nothing to backfill it
-- with: every row that already exists was sent before anyone was listening for a report about it.
--
ALTER TABLE email_outbox ADD COLUMN provider_message_id  varchar(200);
ALTER TABLE email_outbox ADD COLUMN delivery_status      varchar(32);
ALTER TABLE email_outbox ADD COLUMN delivery_reported_at timestamp(6) WITH TIME ZONE;
ALTER TABLE email_outbox ADD COLUMN delivery_detail      varchar(500);

--
-- The webhook's only query: one row by the id the report names. Unique rather than plain, because
-- Azure's id identifies one send operation and two rows claiming the same one would mean the relay
-- had posted the same message twice - which is the failure a second replica would cause and which
-- OutboxEmailRepository already says has no guard. Better to find out here than to silently record
-- a report against whichever of the two the planner reached first.
--
-- Partial, because every row that predates this migration has a null id and a null is not a
-- duplicate of anything in Postgres anyway; the WHERE clause says so out loud and keeps the index
-- to the rows that can actually be looked up.
--
CREATE UNIQUE INDEX idx_email_outbox_provider_message_id
    ON email_outbox (provider_message_id)
    WHERE provider_message_id IS NOT NULL;
