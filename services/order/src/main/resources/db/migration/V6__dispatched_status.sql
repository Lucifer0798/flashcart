-- The order learns that the parcel left.
--
-- ADR 0033. SHIPPED has always meant "a consignment record exists" rather than "it has gone", and
-- the difference is exactly the window in which a customer may still cancel. The boundary lived
-- only inside shipping, so the order service had to ask over the bus and wait to be refused.
--
-- Mirrors OrderStatus, as V1 said it does. Without this the new state is refused by the database at
-- the moment a warehouse operator first dispatches a parcel.
alter table orders drop constraint ck_orders_status;
alter table orders add constraint ck_orders_status check (status in (
    'CREATED', 'RESERVED', 'PAYMENT_PENDING', 'PAID', 'FULFILLING', 'SHIPPED', 'DISPATCHED',
    'DELIVERED', 'PAYMENT_FAILED', 'RESERVATION_EXPIRED', 'PAYMENT_TIMEOUT',
    'CANCELLATION_REQUESTED', 'CANCELLED'));
