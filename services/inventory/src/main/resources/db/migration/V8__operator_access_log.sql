-- ADR 0067: who read whose reservations, when they were not their owner.
--
-- The same table order, payment, shipping and user keep (ADR 0025), and per service for the same reason:
-- the record of a read is written on the connection the read came from, so a failure to record is a
-- failure to read. Until now this service had a staff read of a customer's data and nowhere to write it.
create table operator_access_log (
    id             bigserial    primary key,
    -- The token subject, not the email: emails change and this record has to stay true.
    operator_id    varchar(100) not null,
    action         varchar(40)  not null,
    -- Null for a listing, which has no single subject row.
    resource_id    varchar(100),
    -- Whose data it was. Always known, because ownership is what made this worth recording.
    customer_id    varchar(100) not null,
    -- Ties the row to the logs and the trace for the same request.
    correlation_id varchar(64),
    read_at        timestamptz  not null default now()
);

-- Asked from opposite ends: "who has looked at this customer's data" during a complaint, and "what did
-- this member of staff look at" during a review.
create index idx_operator_access_customer on operator_access_log (customer_id, read_at desc);
create index idx_operator_access_operator on operator_access_log (operator_id, read_at desc);

comment on table operator_access_log is
    'Staff reads of another customer''s data. Not written for a customer reading their own.';
