-- ADR 0046: the user service sends the platform's first outbound message.
--
-- Inventory decides who is told a SKU is back (ADRs 0044, 0045) and publishes BackInStock. Until now
-- nothing received it: a shopper found out only by asking the API. This service owns how to reach a
-- person, so it is the one that sends.

-- The same two tables every other service has. This service publishes nothing yet, but taking Kafka
-- onto the classpath switches on the shared outbox relay and metrics, which read outbox_messages; and
-- consuming once-only needs processed_events. Identical to the other services' rather than a variant,
-- so the shared code meets the schema it was written against.
create table outbox_messages (
    id             uuid         primary key,
    topic          varchar(200) not null,
    message_key    varchar(100),
    event_id       varchar(64)  not null,
    event_type     varchar(100) not null,
    correlation_id varchar(64),
    payload        jsonb        not null,
    created_at     timestamptz  not null default now(),
    published_at   timestamptz,
    attempts       integer      not null default 0,
    last_error     varchar(500),
    trace_parent   varchar(64),
    constraint uq_outbox_event_id unique (event_id)
);

create index ix_outbox_unpublished on outbox_messages (created_at, id) where published_at is null;

create table processed_events (
    event_id     varchar(64)  not null,
    consumer     varchar(100) not null,
    processed_at timestamptz  not null default now(),
    constraint pk_processed_events primary key (event_id, consumer)
);

create index ix_processed_events_at on processed_events (processed_at);

-- One row per place in a queue that was told, and what became of telling the person.
--
-- Received and sent are separate steps on purpose. Receiving is a database insert inside the Kafka
-- handler's transaction, so it never waits on a mail server. Sending is a scheduled job that retries
-- with backoff, so a mail server that is down for an hour delays notices rather than dead-lettering
-- them after the consumer's few seconds of retries.
create table back_in_stock_notices (
    -- The waitlist entry, not the event id. A notice is per place in the queue; a redelivered or
    -- re-published event for the same place must not become a second email.
    waitlist_entry_id uuid         primary key,
    customer_id       varchar(100) not null,
    sku               varchar(64)  not null,
    notified_at       timestamptz  not null,
    held_until        timestamptz,
    -- PENDING until sent or given up on. SENT and SKIPPED are final.
    status            varchar(16)  not null,
    -- Why a notice was not sent: NO_RECIPIENT (no such user), HOLD_LAPSED (the unit held for them was
    -- already released by the time mail could go). Null otherwise.
    skip_reason       varchar(32),
    attempts          integer      not null default 0,
    next_attempt_at   timestamptz  not null default now(),
    last_error        varchar(500),
    sent_at           timestamptz,
    created_at        timestamptz  not null default now(),
    constraint ck_back_in_stock_notices_status check (status in ('PENDING', 'SENT', 'SKIPPED')),
    constraint ck_back_in_stock_notices_sent check ((status = 'SENT') = (sent_at is not null)),
    constraint ck_back_in_stock_notices_skipped check ((status = 'SKIPPED') = (skip_reason is not null))
);

-- The sender's queue: due PENDING rows, oldest first. Partial, because once sent a row is history.
create index ix_back_in_stock_notices_due
    on back_in_stock_notices (next_attempt_at) where status = 'PENDING';
