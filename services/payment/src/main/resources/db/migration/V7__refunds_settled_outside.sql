-- ADR 0041: an abandoned refund can be recorded as paid outside the platform.
--
-- ADR 0031 bounded the refund retry and, deliberately, gave nobody a button to move money instead:
-- an abandoned refund is settled outside the platform, through the provider's own console or a bank
-- transfer. What it left without any supported route was the platform finding out. The only way to
-- stop a REFUND_FAILED row claiming the money was still owed was SQL typed by hand.
--
-- REFUNDED_OUTSIDE is that record. It moves no money; it says money moved somewhere else, who said
-- so, and under what reference.

alter table payments drop constraint ck_payments_status;
alter table payments add constraint ck_payments_status check (status in (
    'PENDING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'REFUNDED', 'REFUND_FAILED', 'REFUNDED_OUTSIDE'));

-- The operator's token subject, not an email, for the reason operator_access_log gives (ADR 0025):
-- an email can be changed, and the record must keep naming the same person.
alter table payments add column refund_settled_by varchar(100);

comment on column payments.refund_settled_by is
    'Who recorded a refund made outside the platform. Set only on REFUNDED_OUTSIDE.';

-- A manual settlement must say who made it. Enforced here as well as in the service, because the
-- one way this status is likeliest to be written wrongly is the hand-typed UPDATE it replaces.
alter table payments add constraint ck_payments_manual_refund_attributed
    check (status <> 'REFUNDED_OUTSIDE' or (refund_settled_by is not null and refund_reference is not null));
