-- FlashCart order schema.
--
-- The order is the aggregate that ties the whole platform together: it is what a customer committed
-- to, and its status is the single fact every other service reacts to. The state names live in
-- flashcart-common (OrderStatus) rather than here, because they travel on the event bus from
-- Phase 5 — inventory, payment and shipping all react to transitions they do not own.
--
-- This service holds no stock counts and no card details. It orchestrates; it does not duplicate.

create table orders (
    id            uuid          primary key,
    -- Human-facing reference, e.g. FC-7K3M9Q2X. Customers quote this at support; the UUID is for
    -- machines. Separate columns because a support-friendly id and a good primary key are not the
    -- same thing.
    order_number  varchar(32)   not null,

    -- Opaque for now. The user service arrives in a later phase; until then an order references a
    -- customer by whatever string the caller supplies, exactly as inventory does.
    customer_id   varchar(100)  not null,

    status        varchar(24)   not null,

    -- Set when this order was placed against a flash sale, so the allocation and per-customer cap
    -- are enforced by inventory. Null for ordinary shopping.
    flash_sale_id uuid,

    currency      varchar(3)    not null default 'USD',
    -- Priced from catalog at order time, never from anything the client sent. Stored because a
    -- historical order must show what was actually charged, not today's price.
    subtotal      numeric(12,2) not null,
    total         numeric(12,2) not null,

    -- The key handed to inventory. Reusing the order id makes inventory's reserve idempotent for
    -- free, which is what lets a timed-out reserve be retried safely rather than guessed about.
    reservation_key      varchar(100),
    -- Mirrored from inventory's reply. The reconciler uses it to find orders whose hold has lapsed
    -- without waiting to be told.
    reservation_expires_at timestamptz,

    -- The caller's idempotency key. A retried checkout — a double-tapped button, a client retry —
    -- must return the original order rather than place a second one.
    idempotency_key varchar(100) not null,

    -- Why an order ended up cancelled, in plain words, for support to read.
    cancellation_reason varchar(300),

    -- Optimistic lock. Several things move an order at once: the customer cancelling, a payment
    -- callback, the expiry reconciler. Two of them landing together must not silently interleave.
    version       bigint        not null default 0,
    created_at    timestamptz   not null default now(),
    updated_at    timestamptz   not null default now(),

    constraint uq_orders_number unique (order_number),
    constraint uq_orders_idempotency_key unique (idempotency_key),
    constraint ck_orders_subtotal check (subtotal >= 0),
    constraint ck_orders_total    check (total >= 0),
    -- Mirrors OrderStatus in flashcart-common. Deliberately duplicated as a CHECK: a typo in a
    -- migration or a hand-written UPDATE should be refused, not persisted as a state no code knows.
    constraint ck_orders_status check (status in (
        'CREATED', 'RESERVED', 'PAYMENT_PENDING', 'PAID', 'FULFILLING', 'SHIPPED', 'DELIVERED',
        'PAYMENT_FAILED', 'RESERVATION_EXPIRED', 'PAYMENT_TIMEOUT', 'CANCELLED'))
);

create index ix_orders_customer on orders (customer_id, created_at desc);
create index ix_orders_status on orders (status, created_at desc);

-- Serves the expiry reconciler: "which held orders have run out of time". Partial, because only
-- RESERVED orders are ever candidates and they are a small minority once a sale is over.
create index ix_orders_reservation_expiry on orders (reservation_expires_at)
    where status = 'RESERVED';

create table order_lines (
    id           uuid          primary key,
    order_id     uuid          not null references orders (id) on delete cascade,
    sku          varchar(64)   not null,
    -- Copied from catalog at order time. A product can be renamed or archived afterwards, and a
    -- historical order must still say what the customer actually bought.
    product_name varchar(200)  not null,
    quantity     integer       not null,
    unit_price   numeric(12,2) not null,
    line_total   numeric(12,2) not null,

    constraint uq_order_lines_order_sku unique (order_id, sku),
    constraint ck_order_lines_quantity   check (quantity > 0),
    constraint ck_order_lines_unit_price check (unit_price >= 0),
    constraint ck_order_lines_total      check (line_total >= 0)
);

create index ix_order_lines_sku on order_lines (sku);

-- Every transition the state machine allowed, in order.
--
-- The orders table holds where an order *is*; this holds how it got there. Without it, "why is this
-- order cancelled" is answerable only by reading application logs that may have rotated away — and
-- an order's path through the machine is exactly what support and reconciliation need to see.
create table order_status_history (
    id             uuid         primary key,
    order_id       uuid         not null references orders (id) on delete cascade,
    -- Null for the very first entry, where the order came into existence.
    from_status    varchar(24),
    to_status      varchar(24)  not null,
    reason         varchar(300),
    -- Ties the transition back to the request or job that caused it.
    correlation_id varchar(64),
    created_at     timestamptz  not null default now()
);

create index ix_order_status_history_order on order_status_history (order_id, created_at);
