-- FlashCart inventory schema.
--
-- This service owns the one number the platform cannot afford to get wrong: how many units are
-- actually available. Catalog states what a flash sale *intends* to sell; everything here is about
-- enforcing that under thousands of concurrent buyers.
--
-- Two design rules run through the whole file:
--
--   1. Every quantity invariant is also a CHECK constraint. The application is careful, but "the
--      application is careful" is not a guarantee — a bug in a conditional UPDATE, a bad migration,
--      or someone at a psql prompt should hit a constraint violation, not sell a unit twice.
--
--   2. Nothing here joins to another service. Products live in catalog; the join key is the SKU
--      string, and there is deliberately no foreign key to reach for.

-- ---------------------------------------------------------------------------------------------
-- Stock: on-hand and held quantities per SKU
-- ---------------------------------------------------------------------------------------------

create table stock_items (
    id         uuid        primary key,
    sku        varchar(64) not null,
    -- Physically in the warehouse, including units currently held by an unpaid reservation.
    on_hand    integer     not null default 0,
    -- Held by a live reservation. Available to sell = on_hand - reserved.
    reserved   integer     not null default 0,
    -- Optimistic-lock column for the admin edit path. The hot reservation path does not read this;
    -- it uses a single conditional UPDATE and never does read-then-write at all.
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_stock_items_sku unique (sku),
    constraint ck_stock_items_sku_upper       check (sku = upper(sku)),
    constraint ck_stock_items_on_hand         check (on_hand >= 0),
    constraint ck_stock_items_reserved        check (reserved >= 0),
    -- The invariant. If this ever trips, we tried to oversell and PostgreSQL refused.
    constraint ck_stock_items_not_oversold    check (reserved <= on_hand)
);

-- ---------------------------------------------------------------------------------------------
-- Sale allocations: the ring-fenced slice of stock a flash sale is allowed to sell
-- ---------------------------------------------------------------------------------------------

-- A warehouse may hold 5,000 units while a sale is only permitted to sell 500 of them. Catalog
-- declares that 500 (flash_sale_items.allocated_units); this table is what enforces it, and it is
-- checked *in addition to* stock_items, never instead of it.
create table sale_allocations (
    id                 uuid        primary key,
    flash_sale_id      uuid        not null,
    sku                varchar(64) not null,
    allocated_units    integer     not null,
    -- Held by a live reservation against this sale.
    reserved_units     integer     not null default 0,
    -- Actually sold. Commit moves units from reserved_units to committed_units, so the sum of the
    -- two is what the sale has consumed of its allocation.
    committed_units    integer     not null default 0,
    per_customer_limit integer     not null default 1,
    version            bigint      not null default 0,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint uq_sale_allocations_sale_sku unique (flash_sale_id, sku),
    constraint ck_sale_allocations_sku_upper  check (sku = upper(sku)),
    constraint ck_sale_allocations_allocated  check (allocated_units > 0),
    constraint ck_sale_allocations_reserved   check (reserved_units >= 0),
    constraint ck_sale_allocations_committed  check (committed_units >= 0),
    constraint ck_sale_allocations_limit      check (per_customer_limit > 0),
    -- The sale cannot consume more than it was allocated, whatever the warehouse holds.
    constraint ck_sale_allocations_within_allocation
        check (reserved_units + committed_units <= allocated_units)
);

-- ---------------------------------------------------------------------------------------------
-- Reservations: a time-boxed hold on stock
-- ---------------------------------------------------------------------------------------------

-- The mechanism that lets a customer type a card number without anyone holding a database lock
-- across their think time. A reservation holds stock for a bounded period and then either commits
-- (payment succeeded), releases (explicitly abandoned), or expires (the timer won).
create table reservations (
    id              uuid         primary key,
    -- The caller's idempotency key, normally the order id. Unique, so a retried reserve call — and
    -- at-least-once retries are certain, not hypothetical — returns the original hold instead of
    -- taking a second one.
    reservation_key varchar(100) not null,
    customer_id     varchar(100) not null,
    -- Set when the reservation is against a flash sale, null for ordinary stock.
    flash_sale_id   uuid,
    status          varchar(16)  not null,
    expires_at      timestamptz  not null,
    committed_at    timestamptz,
    released_at     timestamptz,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    constraint uq_reservations_key unique (reservation_key),
    constraint ck_reservations_status check (status in ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED'))
);

-- Serves both the lazy reclaim and the background sweeper. Partial, because only HELD rows are ever
-- candidates and they are a small minority of the table once a sale is over.
create index ix_reservations_expiry on reservations (expires_at) where status = 'HELD';
create index ix_reservations_customer on reservations (customer_id, created_at desc);

-- One reservation can hold several SKUs, and it is all-or-nothing: a basket that cannot be fully
-- held takes nothing at all.
create table reservation_lines (
    id             uuid        primary key,
    reservation_id uuid        not null references reservations (id) on delete cascade,
    sku            varchar(64) not null,
    quantity       integer     not null,
    constraint uq_reservation_lines_reservation_sku unique (reservation_id, sku),
    constraint ck_reservation_lines_quantity check (quantity > 0)
);

-- The lazy reclaim asks "which expired reservations touch this SKU", which walks lines to
-- reservations rather than the other way round.
create index ix_reservation_lines_sku on reservation_lines (sku);

-- ---------------------------------------------------------------------------------------------
-- Per-customer limits: the anti-scalper cap
-- ---------------------------------------------------------------------------------------------

-- consumed_units counts units the customer is *currently holding* plus units they have *already
-- bought* in this sale. Committed units must keep counting — that is the entire point of "one per
-- customer" — while released and expired holds decrement, so someone whose hold timed out is free
-- to try again.
create table customer_sale_limits (
    id             uuid         primary key,
    customer_id    varchar(100) not null,
    flash_sale_id  uuid         not null,
    sku            varchar(64)  not null,
    consumed_units integer      not null default 0,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    constraint uq_customer_sale_limits unique (customer_id, flash_sale_id, sku),
    constraint ck_customer_sale_limits_consumed check (consumed_units >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Movement ledger: why the numbers are what they are
-- ---------------------------------------------------------------------------------------------

-- Append-only. stock_items holds the current balance; this holds how it got there. Without it,
-- "we are three units short" is unanswerable — with it, every change has an actor, a cause and a
-- correlation id linking back to the request that made it.
create table stock_movements (
    id             uuid         primary key,
    sku            varchar(64)  not null,
    type           varchar(16)  not null,
    -- Signed deltas applied to stock_items by this movement, so the ledger replays to the balance.
    on_hand_delta  integer      not null default 0,
    reserved_delta integer      not null default 0,
    reservation_id uuid,
    flash_sale_id  uuid,
    reason         varchar(200),
    correlation_id varchar(64),
    created_at     timestamptz  not null default now(),
    constraint ck_stock_movements_type
        check (type in ('RECEIVED', 'ADJUSTED', 'RESERVED', 'RELEASED', 'COMMITTED', 'EXPIRED'))
);

create index ix_stock_movements_sku_created on stock_movements (sku, created_at desc);
create index ix_stock_movements_reservation on stock_movements (reservation_id);
