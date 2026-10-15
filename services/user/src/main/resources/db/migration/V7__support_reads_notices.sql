-- ADR 0051: support can read what the platform emailed a customer.
--
-- The notices table already records every email: sent, skipped and why, or still retrying and with what
-- error. Nothing but SQL could read it, so "did you ever email me about my refund?" had no answer support
-- could give.

-- The same table payment, shipping and order keep (ADR 0025), for the same reason it is per service: the
-- record of a read is written on the connection the read came from, so a failure to record is a failure
-- to read. Until now this service had nothing a member of staff could read about someone else.
create table operator_access_log (
    id             bigserial    primary key,
    -- The token subject, not the email: emails change and this record has to stay true.
    operator_id    varchar(100) not null,
    action         varchar(40)  not null,
    -- The order number when the read was narrowed to one; null for the customer's whole history.
    resource_id    varchar(100),
    customer_id    varchar(100) not null,
    correlation_id varchar(64),
    read_at        timestamptz  not null default now()
);

create index idx_operator_access_customer on operator_access_log (customer_id, read_at desc);
create index idx_operator_access_operator on operator_access_log (operator_id, read_at desc);

comment on table operator_access_log is
    'Operator reads of another customer''s data. Not written for a customer reading their own.';

-- The question support is asked is about one customer, newest first. The existing indexes serve the
-- sender, and only cover what is still pending.
create index ix_notices_customer on notices (customer_id, occurred_at desc);
