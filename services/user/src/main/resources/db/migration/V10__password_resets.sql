-- ADR 0057: a forgotten password can be reset by email.
--
-- One row per request. The token itself is never stored: the sender mints it when it sends the email and
-- keeps only its SHA-256 here, so neither a database reader nor support's view of the email queue holds
-- anything that would open the account.
create table password_resets (
    id           uuid        primary key,
    user_id      uuid        not null references users (id),
    requested_at timestamptz not null default now(),
    expires_at   timestamptz not null,
    -- Null until the email is sent. Replaced if a failed send is retried, so only the token in the email
    -- that actually went out works.
    token_hash   varchar(64),
    used_at      timestamptz,
    constraint uq_password_resets_token unique (token_hash)
);

create index ix_password_resets_user on password_resets (user_id, requested_at desc);

alter table notices drop constraint ck_notices_kind;
alter table notices add constraint ck_notices_kind check (kind in ('BACK_IN_STOCK', 'ORDER_CONFIRMED',
    'ORDER_DISPATCHED', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUNDED', 'PASSWORD_RESET'));
