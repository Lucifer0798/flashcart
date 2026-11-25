-- ADR 0080: the owner is emailed when their data is downloaded.
alter table notices drop constraint ck_notices_kind;
alter table notices add constraint ck_notices_kind check (kind in ('BACK_IN_STOCK', 'ORDER_CONFIRMED',
    'ORDER_DISPATCHED', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUNDED', 'PASSWORD_RESET', 'PASSWORD_CHANGED',
    'DATA_EXPORTED'));
