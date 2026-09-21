-- Cancelling an order that has already been paid for.
--
-- ADR 0030. The state machine used to allow PAID -> CANCELLED and FULFILLING -> CANCELLED, and
-- taking either edge compensated nothing: the capture stayed with the platform and the committed
-- units did not come back, so a customer who cancelled lost the goods and the money. Those edges are
-- gone. In their place a paid order is cancelled by asking shipping whether the parcel has left, and
-- CANCELLATION_REQUESTED is the state it waits in while shipping answers.

-- Mirrors OrderStatus, as V1 said it does. Without this the new state is refused by the database at
-- the moment a customer first tries to cancel a paid order -- which is to say, in front of them.
alter table orders drop constraint ck_orders_status;
alter table orders add constraint ck_orders_status check (status in (
    'CREATED', 'RESERVED', 'PAYMENT_PENDING', 'PAID', 'FULFILLING', 'SHIPPED', 'DELIVERED',
    'PAYMENT_FAILED', 'RESERVATION_EXPIRED', 'PAYMENT_TIMEOUT', 'CANCELLATION_REQUESTED',
    'CANCELLED'));

-- Orders waiting on shipping for an answer. Partial and normally empty: a request that is still
-- here after a few seconds means the answer was lost, and this is the cheapest way to ask whether
-- any are. There is no sweeper for them yet, which ADR 0030 records as open.
create index ix_orders_cancellation_requested on orders (updated_at)
    where status = 'CANCELLATION_REQUESTED';
