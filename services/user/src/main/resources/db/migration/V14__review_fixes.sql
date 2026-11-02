-- ADR 0062: fixes from reviewing the security work as a whole.

-- The owner is emailed when a reset changes their password.
alter table notices drop constraint ck_notices_kind;
alter table notices add constraint ck_notices_kind check (kind in ('BACK_IN_STOCK', 'ORDER_CONFIRMED',
    'ORDER_DISPATCHED', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUNDED', 'PASSWORD_RESET', 'PASSWORD_CHANGED'));

-- Nobody registers at a domain reserved never to receive mail (RFC 2606). Closing an account moves its email to
-- closed-<id>@closed.invalid; an account already holding that address would make the closure fail on the unique
-- constraint. Only a closed account's placeholder may end in .invalid.
alter table users add constraint ck_users_deliverable_email
    check (status = 'CLOSED' or email not like '%.invalid');
