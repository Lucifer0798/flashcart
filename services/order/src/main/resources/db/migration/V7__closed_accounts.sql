-- ADR 0053: accounts closed by their holders, so their leftover tokens cannot buy.
--
-- Every service verifies a token for itself (ADR 0021), so a token issued before an account closed stays
-- valid here until it expires. Buying is the one harmful thing it can still do, so this service keeps the
-- closed ids, learned from AccountClosed, and refuses a checkout from any of them.
create table closed_accounts (
    customer_id varchar(100) primary key,
    closed_at   timestamptz  not null,
    -- When this service heard, which is when the refusal began. Kept apart from closed_at because the gap
    -- between them is the window in which a leftover token could still have bought.
    learned_at  timestamptz  not null default now()
);
