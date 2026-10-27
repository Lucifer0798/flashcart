-- ADR 0058: failed sign-ins are counted, so guessing a password is slow and stuffing credentials is slower.
--
-- One row per failure per limit it counts against. The key is a SHA-256 of what is being limited -- an
-- address, a client, or the two together -- so the table holds neither the addresses people typed, which
-- include other people's and mistyped ones, nor their IP addresses.
create table sign_in_failures (
    id        bigserial   primary key,
    key_hash  varchar(64) not null,
    failed_at timestamptz not null default now()
);

create index ix_sign_in_failures_key on sign_in_failures (key_hash, failed_at desc);
create index ix_sign_in_failures_age on sign_in_failures (failed_at);
