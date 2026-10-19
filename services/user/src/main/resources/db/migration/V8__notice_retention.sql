-- ADR 0052: finished notices are deleted once they are about something older than the retention window.
--
-- The sweep asks for finished rows by when their event happened. Neither existing index serves that: one
-- covers only what is pending, the other leads with the customer.
create index ix_notices_finished on notices (occurred_at) where status <> 'PENDING';
