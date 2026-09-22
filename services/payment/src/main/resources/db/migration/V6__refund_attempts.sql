-- How many times the provider has refused to reverse this capture.
--
-- ADR 0030 shipped REFUND_FAILED with no way out of it: the alert fired and there was nothing that
-- would try again. ADR 0031 adds a bounded retry, and bounded needs a count.

alter table payments add column refund_attempts integer not null default 0;

-- Existing REFUND_FAILED rows, if any, are credited with the attempt that produced them. Without
-- this they would read as never having been tried, which is the opposite of true.
update payments set refund_attempts = 1 where status = 'REFUND_FAILED';

-- The retry job claims rows below the cap; the partial index from V5 already serves the "what is
-- still owed" question and covers this one too, since it is the same small set.
-- A payment at the configured cap is no longer retried automatically. That is not the same as the
-- money no longer being owed.
comment on column payments.refund_attempts is 'Refusals so far.';
