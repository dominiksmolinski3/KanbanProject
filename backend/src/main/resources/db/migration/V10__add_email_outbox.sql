--
-- The table that makes "the account exists" and "somebody will be told the code" one decision.
--
-- Until now they were two, in two systems, with no order that made them safe. Signup wrote a user
-- row and made an HTTPS call to Azure; whichever ran first, the other could fail on its own. A
-- provider having a bad minute meant an account written, a code stored, and a 500 shown to the
-- person signing up - who could do nothing about any of it, and whose account was in a state only a
-- resend could get out of.
--
-- A queue does not fix that by itself: Service Bus is a second system too, and an enqueue after the
-- commit is lost if the process dies in between. A row in this database, written in the same
-- transaction as the account, either happens with it or does not happen at all. That is the outbox
-- pattern, and the reason it is a table rather than a client library. When Service Bus does arrive
-- it goes behind the relay, and this table is what feeds it.
--
-- The message is stored composed, both bodies, exactly as it was built. Storing the facts and
-- re-running the template at send time would mean a message queued before a wording change goes out
-- with the new wording, and a message queued before a template bug cannot be replayed as it was
-- meant to read.
--
-- These rows hold live credentials: a pending verification or reset row carries a code that is
-- currently redeemable. Nothing logs a body, the failure column stores the provider's complaint
-- rather than the message, and read access to this table is read access to every code in flight.
--
-- status is the name rather than an ordinal, because the one thing this table is for is being read
-- by a person asking why a mail did not arrive, and a column of small integers answers that badly:
--
--   PENDING - written, not yet accepted by the provider; the only status the relay looks at
--   SENT    - the provider took it. Not the same as delivered, which nothing here can see
--   FAILED  - refused often enough that the relay gave up; last_error says what it said
--   DROPPED - no mail account was configured when the relay reached it, which is a fresh clone and
--             CI rather than a fault
--
-- next_attempt_at is when the relay may next take the row: set to created_at so the first pass
-- picks it up, and pushed out on each refusal. Keeping the backoff in the row rather than in the
-- relay is what lets one unreachable recipient wait without holding up the batch, and what lets a
-- restart resume where it left off instead of retrying everything at once.
--
CREATE TABLE email_outbox
(
    id              bigserial                   PRIMARY KEY,
    recipient       varchar(320)                NOT NULL,
    subject         varchar(255)                NOT NULL,
    html_body       text                        NOT NULL,
    text_body       text                        NOT NULL,
    status          varchar(16)                 NOT NULL,
    attempts        integer                     NOT NULL,
    created_at      timestamp(6) WITH TIME ZONE NOT NULL,
    next_attempt_at timestamp(6) WITH TIME ZONE NOT NULL,
    sent_at         timestamp(6) WITH TIME ZONE,
    last_error      varchar(500)
);

--
-- The relay's only query: the pending rows that are due, oldest first. Without this it is a
-- sequential scan every minute over a table that keeps its sent rows forever.
--
CREATE INDEX idx_email_outbox_due ON email_outbox (status, next_attempt_at, id);
