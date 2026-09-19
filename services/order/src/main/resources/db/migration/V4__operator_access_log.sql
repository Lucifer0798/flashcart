-- Who read whose order, when they were not its owner.
--
-- ADR 0025 recorded that an operator could read any customer's payment and parcel but not their
-- order, and that changing it needed its own record. ADR 0028 is that record: the asymmetry was an
-- accident of build order rather than a boundary, since the payment and shipment an operator can
-- already read disclose the customer, the order number, the amount and the shipped lines.
--
-- Identical to the table in payment and shipping, deliberately. It is copied rather than shared for
-- the reason database-per-service exists (ADR 0003), and for the sharper one ADR 0025 gives: the
-- insert happens on the same connection as the read it records, so recording adds no dependency the
-- read did not already have. That property is what lets a failure to record fail the read.
create table operator_access_log (
    id             bigserial    primary key,
    -- The token subject, not the email: emails change and this record has to stay true.
    operator_id    varchar(100) not null,
    action         varchar(40)  not null,
    -- The order number. Null for a listing, which has no single subject row.
    resource_id    varchar(100),
    -- Whose order it was. Always known, because ownership is what made this worth recording.
    customer_id    varchar(100) not null,
    -- Ties the row to the logs and the trace for the same request.
    correlation_id varchar(64),
    read_at        timestamptz  not null default now()
);

-- The two questions anybody actually asks, and they are asked from opposite ends: "who has looked at
-- this customer's orders" during a complaint, and "what did this operator look at" during a review.
-- A single index would serve one of them and table-scan for the other.
create index idx_operator_access_customer on operator_access_log (customer_id, read_at desc);
create index idx_operator_access_operator on operator_access_log (operator_id, read_at desc);

comment on table operator_access_log is
    'Operator reads of another customer''s orders. Not written for a customer reading their own.';
