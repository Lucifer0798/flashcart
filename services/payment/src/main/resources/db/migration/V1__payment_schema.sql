-- FlashCart payment schema.
--
-- This service holds no card details and never will: it records that money was asked for and what
-- the provider said. Storing a PAN would put the whole platform in scope for PCI-DSS in exchange for
-- nothing, since the provider is the only thing that needs it.

create table payments (
    id                  uuid          primary key,
    order_id            uuid          not null,
    order_number        varchar(32)   not null,
    customer_id         varchar(100)  not null,

    amount              numeric(12,2) not null,
    currency            varchar(3)    not null,

    status              varchar(16)   not null,

    -- The order id, sent through to the provider. A customer charged twice for one order is the
    -- most expensive possible consequence of at-least-once delivery, so this is unique here *and*
    -- travels down to the provider call.
    idempotency_key     varchar(100)  not null,

    -- Whatever the provider calls this attempt. Null while pending, and null forever if the provider
    -- never answered — which is exactly the case reconciliation has to resolve by other means.
    provider_reference  varchar(100),
    failure_code        varchar(64),
    failure_reason      varchar(300),

    requested_at        timestamptz   not null default now(),
    settled_at          timestamptz,
    version             bigint        not null default 0,
    created_at          timestamptz   not null default now(),
    updated_at          timestamptz   not null default now(),

    constraint uq_payments_idempotency_key unique (idempotency_key),
    constraint ck_payments_amount check (amount > 0),
    -- TIMED_OUT is a first-class outcome, not a flavour of FAILED. FAILED means the provider said
    -- no and nothing was charged; TIMED_OUT means nobody knows, and the two demand opposite
    -- responses from the order saga.
    constraint ck_payments_status check (status in ('PENDING', 'COMPLETED', 'FAILED', 'TIMED_OUT'))
);

create index ix_payments_order on payments (order_id);
create index ix_payments_customer on payments (customer_id, created_at desc);
-- Serves the reconciler: "which attempts have been sitting unanswered".
create index ix_payments_pending on payments (requested_at) where status = 'PENDING';
