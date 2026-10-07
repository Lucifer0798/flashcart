-- ADR 0045: being told a SKU is back means a unit is held for you.
--
-- A waiter told under ADR 0044 now also gets a reservation of one unit, keyed waitlist:<entry id>, for
-- a short window. When that customer's own reservation for the SKU arrives -- their checkout -- it
-- adopts the hold instead of taking fresh stock. ADOPTED is the hold's state afterwards: it did not
-- expire, was not released and was not sold; it became part of somebody's order.

alter table reservations drop constraint ck_reservations_status;
alter table reservations add constraint ck_reservations_status
    check (status in ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RETURNED', 'ADOPTED'));

-- The ledger entry for an adoption moves nothing: the unit was reserved when the hold was made and stays
-- reserved for the order that adopted it. Zero deltas keep the ledger replaying to the balance, and keep
-- an adoption from reading as units coming back.
alter table stock_movements drop constraint ck_stock_movements_type;
alter table stock_movements add constraint ck_stock_movements_type
    check (type in ('RECEIVED', 'ADJUSTED', 'RESERVED', 'RELEASED', 'COMMITTED', 'EXPIRED', 'RETURNED', 'ADOPTED'));

-- Finding a customer's live waitlist holds for a SKU, at checkout. Partial: only waitlist holds still
-- HELD are ever candidates.
create index ix_reservations_waitlist_holds
    on reservations (customer_id, expires_at)
    where status = 'HELD' and reservation_key like 'waitlist:%';
