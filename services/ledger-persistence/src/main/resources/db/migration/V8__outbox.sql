-- V8 - the transactional outbox.
--
-- REQ-EVT-001: an event cannot be published without its postings committing. The naive arrangement
-- is to post to the database and then send to Kafka, and it has two failure modes that are both
-- unacceptable in a bank: the send fails after the commit and the estate never learns the money
-- moved, or the send succeeds and the transaction rolls back and every downstream tier acts on a
-- transfer that does not exist. No amount of retrying fixes either; they are the dual-write problem,
-- and the only answer is not to dual-write.
--
-- So the event is written here, in the same transaction as the postings, and a relay publishes from
-- this table afterwards. The cost is at-least-once delivery, because the relay can crash between
-- publishing and marking the row dispatched. The AsyncAPI document states that and requires every
-- consumer to de-duplicate on transferRef.

CREATE TABLE outbox_record (
    id             bigint      GENERATED ALWAYS AS IDENTITY,

    -- The destination, resolved when the row is written rather than by the relay. A relay that chose
    -- topics would be a second place the routing lives, and the two would drift.
    topic          varchar(96) NOT NULL,

    -- transferRef. The partition key, so every event for one transfer keeps its order.
    message_key    varchar(34) NOT NULL,

    payload        jsonb       NOT NULL,

    created_at     timestamptz NOT NULL DEFAULT now(),

    -- Null until the broker has acknowledged it. This column is the whole state machine.
    dispatched_at  timestamptz NULL,

    -- How many times the relay has tried. Not a retry limit: nothing here gives up, because giving
    -- up on an event silently is how a ledger and its consumers diverge. It is what an operator
    -- looks at, and what the outbox-lag metric is derived from.
    attempts       integer     NOT NULL DEFAULT 0,

    -- Truncated deliberately. A broker's error can be long and it is a diagnostic, not a record.
    last_error     varchar(500) NULL,

    CONSTRAINT outbox_record_pk PRIMARY KEY (id),
    CONSTRAINT outbox_attempts_not_negative CHECK (attempts >= 0)
);

-- The relay's only query: the oldest rows that have not been dispatched. A partial index holds just
-- those, so it stays small however large the table grows - which matters because the table is never
-- pruned by the relay and a full index over every event ever published would be mostly dead weight.
CREATE INDEX outbox_record_pending_ix ON outbox_record (id) WHERE dispatched_at IS NULL;
