-- ADR 0059: access tokens last fifteen minutes, and a refresh token keeps a session going.
--
-- Every service verifies an access token for itself, so one cannot be withdrawn once issued. What can be
-- withdrawn is the means of getting the next one. That is this table.
create table refresh_tokens (
    id         uuid        primary key,
    user_id    uuid        not null references users (id),
    -- One sign-in and every token rotated from it. Revoked together when a used token comes back, which
    -- only happens if somebody else has a copy.
    family_id  uuid        not null,
    -- SHA-256 of the token. The token itself is handed to the client and never kept.
    token_hash varchar(64) not null,
    issued_at  timestamptz not null default now(),
    expires_at timestamptz not null,
    -- Set when this token is exchanged for the next. A second exchange of the same token is a theft.
    used_at    timestamptz,
    revoked_at timestamptz,
    constraint uq_refresh_tokens_hash unique (token_hash)
);

create index ix_refresh_tokens_user on refresh_tokens (user_id) where revoked_at is null;
create index ix_refresh_tokens_family on refresh_tokens (family_id);
