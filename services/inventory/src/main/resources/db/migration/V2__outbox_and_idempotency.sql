-- Phase 8: the transactional outbox, and a record of what this service has already consumed.
--
-- Both tables exist in every service that publishes or consumes, rather than in a shared database,
-- because both must be written in the *same transaction* as the business change they accompany.
-- A shared table would put them in a different transaction and reopen the exact gap they close.

-- Messages queued for Kafka, written in the caller's transaction.
--
-- Before this, a service committed its state change and then published — two systems, one of them
-- not transactional. A crash in between lost the event and stalled the saga; publishing first and
-- rolling back told a downstream to act on something that never happened. No ordering fixes that,
-- because the problem is that they are two operations.
create table outbox_messages (
    id             uuid         primary key,
    topic          varchar(200) not null,
    -- The Kafka partition key: the aggregate id, so one order's messages stay in sequence.
    message_key    varchar(100),
    -- Unique, so a caller retrying its own transaction cannot queue the same message twice.
    event_id       varchar(64)  not null,
    event_type     varchar(100) not null,
    correlation_id varchar(64),
    -- The exact JSON the direct publisher would have produced. The relay sends these bytes
    -- unchanged rather than re-serialising, so both paths put an identical wire format on the topic.
    payload        jsonb        not null,

    created_at     timestamptz  not null default now(),
    -- Null until the broker has acknowledged it. Never set optimistically: marking before the ack
    -- would reintroduce precisely the loss this table exists to prevent.
    published_at   timestamptz,
    attempts       integer      not null default 0,
    last_error     varchar(500),

    constraint uq_outbox_event_id unique (event_id)
);

-- Serves the relay's claim query. Partial, because a published row is never looked at again and the
-- table is overwhelmingly published rows within moments of a sale ending.
create index ix_outbox_unpublished on outbox_messages (created_at, id) where published_at is null;

-- Which messages this service has already applied, and as which consumer.
--
-- Replaces the Phase 5 approach of inferring "already processed" from "the state machine says this
-- transition is not legal" — usually the same answer, but not the same statement: that inference
-- silently swallowed genuinely impossible transitions, which are bugs, alongside harmless duplicates.
create table processed_events (
    event_id     varchar(64)  not null,
    -- Keyed per consumer, not per event: several consumers legitimately handle the same message and
    -- each must handle it once. A single "seen" flag would let the first one silence the rest.
    consumer     varchar(100) not null,
    processed_at timestamptz  not null default now(),

    constraint pk_processed_events primary key (event_id, consumer)
);

-- Lets old rows be pruned by age once a retention policy exists; without an index that sweep would
-- table-scan a table that only ever grows.
create index ix_processed_events_at on processed_events (processed_at);
