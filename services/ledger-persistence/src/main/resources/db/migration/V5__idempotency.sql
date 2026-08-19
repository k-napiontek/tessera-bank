-- V5 - the record of which money-moving requests have already been carried out.
--
-- The whole point of this table is its primary key. A client that retries a transfer after a timeout
-- must move money once, and the only mechanism that survives two retries arriving at the same
-- millisecond on two different connections is a uniqueness constraint the database enforces. An
-- application-level "have I seen this key?" check is passed by both retries and posts twice.
--
-- The stored response is kept verbatim rather than re-rendered on replay. Re-rendering would pick up
-- whatever has changed since - a balance, a status, a serialisation detail - and the client would get
-- a different document for the same request, which is precisely the promise idempotency makes.

CREATE TABLE idempotency_record (
    -- Client-supplied and opaque. The contract fixes the width at 16 to 64 characters.
    key             varchar(64) NOT NULL,

    -- SHA-256, hex, of the method, the resolved path and the canonical request body. Comparing
    -- fingerprints rather than bodies means the table never stores what the client sent.
    fingerprint     char(64)    NOT NULL,

    -- Null between the claim and the response being stored, which is only ever within one
    -- transaction. A row committed with a null status would mean a request that claimed its key and
    -- then vanished, and the constraint below makes that state unrepresentable on commit.
    status          integer,
    response_body   text,

    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_record_pk PRIMARY KEY (key),
    CONSTRAINT idempotency_key_width CHECK (length(key) BETWEEN 16 AND 64),
    CONSTRAINT idempotency_status_valid CHECK (status IS NULL OR status BETWEEN 100 AND 599),

    -- A status without a body, or a body without a status, is a half-recorded response. A replay
    -- would then answer with one and not the other.
    CONSTRAINT idempotency_response_complete CHECK ((status IS NULL) = (response_body IS NULL))
);

-- WP-09 will want to expire these. Nothing does yet, and an index nothing reads is a cost with no
-- reader, so this one exists because the retention sweep is the obvious next use of the table.
CREATE INDEX idempotency_record_created_ix ON idempotency_record (created_at);
