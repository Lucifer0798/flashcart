-- Giving the money back.
--
-- ADR 0030: cancelling an order after it was paid used to compensate nothing -- the capture stayed
-- with the platform and the order went to CANCELLED regardless. A refund needs two things the
-- schema did not have: somewhere to record that it happened, and permission for the row to say so.

alter table payments add column refunded_at      timestamptz;
alter table payments add column refund_reference varchar(100);

-- settled_at is deliberately left alone. It records when the money moved, and it did move; a
-- refunded payment is the row that most needs to remember the customer was charged at all.
comment on column payments.refunded_at is 'When the capture was reversed.';

-- The CHECK is the reason this migration exists at all. V1 listed the four statuses that existed
-- then, so a REFUNDED row would be refused by the database -- after the provider had already sent
-- the money back. The constraint deliberately mirrors PaymentStatus; keeping them in step is the
-- price of having it, and it is worth paying, because the alternative is a status no code knows
-- being written successfully.
alter table payments drop constraint ck_payments_status;
alter table payments add constraint ck_payments_status check (status in (
    'PENDING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'REFUNDED', 'REFUND_FAILED'));

-- Money the platform owes and has not returned: the provider refused to reverse a capture for an
-- order that is already cancelled. Partial, because in a healthy system this index is empty, and an
-- index that is normally empty is the cheapest possible way to ask "is it still?".
create index ix_payments_refund_failed on payments (updated_at desc)
    where status = 'REFUND_FAILED';
