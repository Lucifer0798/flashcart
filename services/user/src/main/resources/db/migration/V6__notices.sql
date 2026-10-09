-- ADR 0048: one queue for every email, not one per kind.
--
-- V4 made back_in_stock_notices for the first outbound message. Order emails -- confirmed, dispatched,
-- delivered, cancelled, refunded -- need the same guarantees (once per thing, retried until sent, not
-- sent stale), and a second and third table would each have to re-earn them. So the table generalises
-- and the back-in-stock rows move into it.

create table notices (
    -- What makes two announcements the same email: 'back-in-stock:<entry>', 'order-confirmed:<order>',
    -- 'refunded:<order>'. A redelivered or re-published event lands on the same key and is dropped.
    notice_key      varchar(150) primary key,
    kind            varchar(32)  not null,
    customer_id     varchar(100),
    -- The order this email is about, when it is about one. Emails for one order go out in the order the
    -- events happened, so a retried "confirmed" is never overtaken by "dispatched".
    order_number    varchar(32),
    -- When the event happened, by the publisher's clock: the sequence within an order. Not created_at,
    -- which is when this service heard, and separate consumer groups can hear out of order.
    occurred_at     timestamptz  not null,
    -- What the email says: the SKU, the reason, the amount. Small, and rendered by kind.
    payload         jsonb        not null default '{}'::jsonb,
    -- After this, the email would be false and is not sent: the unit held for a back-in-stock shopper
    -- has gone to the next in line. Null for emails that stay true.
    send_before     timestamptz,
    status          varchar(16)  not null,
    skip_reason     varchar(32),
    attempts        integer      not null default 0,
    next_attempt_at timestamptz  not null default now(),
    last_error      varchar(500),
    sent_at         timestamptz,
    created_at      timestamptz  not null default now(),
    constraint ck_notices_kind check (kind in ('BACK_IN_STOCK', 'ORDER_CONFIRMED', 'ORDER_DISPATCHED',
        'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUNDED')),
    constraint ck_notices_status check (status in ('PENDING', 'SENT', 'SKIPPED')),
    constraint ck_notices_skip_reason check (skip_reason is null or skip_reason in ('STALE', 'NO_RECIPIENT')),
    constraint ck_notices_sent check ((status = 'SENT') = (sent_at is not null)),
    constraint ck_notices_skipped check ((status = 'SKIPPED') = (skip_reason is not null))
);

create index ix_notices_due on notices (next_attempt_at) where status = 'PENDING';
create index ix_notices_order on notices (order_number, occurred_at) where status = 'PENDING';

-- Carry the back-in-stock queue across, whatever state it is in. A pending one keeps its place and its
-- attempt count; HOLD_LAPSED becomes STALE, the general name for the same thing.
insert into notices (notice_key, kind, customer_id, order_number, occurred_at, payload, send_before,
                     status, skip_reason, attempts, next_attempt_at, last_error, sent_at, created_at)
select 'back-in-stock:' || waitlist_entry_id, 'BACK_IN_STOCK', customer_id, null, notified_at,
       -- The hold travels in the payload, as new notices carry it: send_before is now also a shelf life,
       -- so the email cannot infer a hold from it.
       jsonb_build_object('sku', sku)
         || case when held_until is null then '{}'::jsonb
                 else jsonb_build_object('heldUntil',
                        to_char(held_until at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')) end,
       held_until,
       status, case skip_reason when 'HOLD_LAPSED' then 'STALE' else skip_reason end,
       attempts, next_attempt_at, last_error, sent_at, created_at
  from back_in_stock_notices;

drop table back_in_stock_notices;
