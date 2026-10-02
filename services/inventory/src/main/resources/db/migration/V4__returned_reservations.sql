-- ADR 0040: a sale can be undone, and the units come back.
--
-- Until now COMMITTED was the end of a reservation's life. ADR 0030 made a paid order cancellable
-- and refundable, but left the committed units where commit put them -- gone from on_hand, counted
-- against the sale's allocation and against the customer's cap -- so every cancelled paid order
-- shrank the sale by its quantity for good.
--
-- RETURNED is the state a committed reservation moves to when shipping confirms the goods never
-- left. It is distinct from RELEASED on purpose: a release gives back a hold that never became a
-- sale, a return gives back a sale, and support needs to tell those apart.

alter table reservations drop constraint ck_reservations_status;
alter table reservations add constraint ck_reservations_status
    check (status in ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RETURNED'));

-- Set only on RETURNED. committed_at is deliberately kept: the sale did happen, and the reservation
-- records both that it happened and that it was undone.
alter table reservations add column returned_at timestamptz;

alter table stock_movements drop constraint ck_stock_movements_type;
alter table stock_movements add constraint ck_stock_movements_type
    check (type in ('RECEIVED', 'ADJUSTED', 'RESERVED', 'RELEASED', 'COMMITTED', 'EXPIRED', 'RETURNED'));
