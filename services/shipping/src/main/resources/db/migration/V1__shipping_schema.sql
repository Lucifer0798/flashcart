-- FlashCart shipping schema.
--
-- A shipment is created once payment has settled, never before: handing goods to a carrier for money
-- that has not arrived is the one mistake in this service that costs real inventory.

create table shipments (
    id              uuid          primary key,
    order_id        uuid          not null,
    order_number    varchar(32)   not null,
    customer_id     varchar(100)  not null,

    status          varchar(16)   not null,
    carrier         varchar(64)   not null,
    tracking_number varchar(64)   not null,

    dispatched_at   timestamptz,
    delivered_at    timestamptz,
    version         bigint        not null default 0,
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),

    -- One shipment per order. Also the idempotency guard: a redelivered CreateShipment command
    -- finds the existing row rather than booking a second consignment.
    constraint uq_shipments_order unique (order_id),
    constraint uq_shipments_tracking unique (tracking_number),
    constraint ck_shipments_status check (status in ('CREATED', 'DISPATCHED', 'DELIVERED', 'CANCELLED'))
);

create index ix_shipments_customer on shipments (customer_id, created_at desc);

create table shipment_lines (
    id          uuid        primary key,
    shipment_id uuid        not null references shipments (id) on delete cascade,
    sku         varchar(64) not null,
    quantity    integer     not null,

    constraint uq_shipment_lines_shipment_sku unique (shipment_id, sku),
    constraint ck_shipment_lines_quantity check (quantity > 0)
);
