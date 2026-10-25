-- ADR 0054: accounts closed by their holders, so their leftover tokens cannot queue for stock.
--
-- ADR 0053 took a closed account out of every waitlist, but a token issued before closing stays valid for
-- up to twelve hours, and it could join again. Each place it made would be told when stock came back and
-- hold a unit for ten minutes that nobody would buy. This service now keeps the closed ids, as the order
-- service does, and refuses a new place for any of them.
create table closed_accounts (
    customer_id varchar(100) primary key,
    closed_at   timestamptz  not null,
    learned_at  timestamptz  not null default now()
);
