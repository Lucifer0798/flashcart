-- ADR 0044: a waitlist for sold-out SKUs.
--
-- A shopper who finds a SKU sold out joins a queue for it. When units become available again --
-- received, adjusted up, a hold released or expired, a sale returned -- the oldest waiters are told,
-- as many as there are new units and no more, each at most once.

create table waitlist_entries (
    id           uuid         primary key,
    sku          varchar(64)  not null,
    customer_id  varchar(100) not null,
    -- WAITING until told or until they leave. NOTIFIED and CANCELLED are both final: a shopper who was
    -- told and missed the unit joins again, at the back, rather than keeping a place they were given.
    status       varchar(16)  not null,
    created_at   timestamptz  not null default now(),
    notified_at  timestamptz,
    cancelled_at timestamptz,
    constraint ck_waitlist_entries_status check (status in ('WAITING', 'NOTIFIED', 'CANCELLED')),
    constraint ck_waitlist_entries_sku_upper check (sku = upper(sku)),
    constraint ck_waitlist_entries_notified check ((status = 'NOTIFIED') = (notified_at is not null)),
    constraint ck_waitlist_entries_cancelled check ((status = 'CANCELLED') = (cancelled_at is not null))
);

-- One place in the queue per shopper per SKU. Partial, so the history of earlier entries -- notified,
-- cancelled -- does not stop anybody joining again.
create unique index uq_waitlist_entries_waiting
    on waitlist_entries (sku, customer_id) where status = 'WAITING';

-- The queue itself: the oldest waiters for a SKU, which is the only order anybody is ever told in.
-- Partial for the same reason as the reservation expiry index -- once a sale is over, almost every row
-- here is history.
create index ix_waitlist_entries_queue
    on waitlist_entries (sku, created_at, id) where status = 'WAITING';

create index ix_waitlist_entries_customer on waitlist_entries (customer_id, created_at desc);
