-- Stopping a consignment before it leaves.
--
-- ShipmentStatus.CANCELLED and the CHECK constraint in V1 have both listed this value since the
-- first migration, and nothing in the platform could ever produce it: there was no way to cancel an
-- order once it had been paid for. ADR 0030 adds one, so the value stops being decoration.
--
-- No constraint change is needed here, which is the difference between this migration and payment's:
-- the schema was already ready for a state the code could not reach.

alter table shipments add column cancelled_at timestamptz;

-- Only ever set from CREATED. A dispatched parcel cannot be recalled by a database write.
comment on column shipments.cancelled_at is 'When the consignment was stopped.';
