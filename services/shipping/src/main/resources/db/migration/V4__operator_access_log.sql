-- Who read whose data, when they were not its owner.
--
-- ADR 0023 opened these read paths to customers and left this gap behind: an operator can read every
-- customer's payment history, and nothing recorded that it happened. ADR 0025 closes it.
--
-- In this service's own database rather than a shared one, for the reason database-per-service exists
-- (ADR 0003) and for a sharper one: the insert happens on the same connection as the read it records,
-- so recording adds no dependency the read did not already have. A central audit database would make
-- every operator read depend on a second system being up, which is how audit logging earns its
-- reputation for breaking the thing it was watching.
create table operator_access_log (
    id             bigserial    primary key,
    -- The token subject, not the email: emails change and this record has to stay true.
    operator_id    varchar(100) not null,
    action         varchar(40)  not null,
    -- The id, order number or tracking number. Null for a listing, which has no single subject row.
    resource_id    varchar(100),
    -- Whose data it was. Always known, because ownership is what made this worth recording.
    customer_id    varchar(100) not null,
    -- Ties the row to the logs and the trace for the same request.
    correlation_id varchar(64),
    read_at        timestamptz  not null default now()
);

-- The two questions anybody actually asks, and they are asked from opposite ends: "who has looked at
-- this customer's data" during a complaint, and "what did this operator look at" during a review.
-- A single index would serve one of them and table-scan for the other.
create index idx_operator_access_customer on operator_access_log (customer_id, read_at desc);
create index idx_operator_access_operator on operator_access_log (operator_id, read_at desc);

comment on table operator_access_log is
    'Operator reads of another customer''s data. Not written for a customer reading their own.';
