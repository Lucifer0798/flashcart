-- ADR 0072: an address is credited for the established accounts that signed in from it, so that many shoppers
-- behind one office or carrier address are not locked out by each other's typos.
--
-- One row per successful sign-in by an account at least a day old. Both columns are hashes, as in
-- throttled_attempts: the client's, and the account-and-client pair's. Distinct pairs for a client are the
-- distinct accounts that proved a password there. Rows older than the client window can no longer count, and
-- are pruned with the failures.
--
-- Unlogged: every successful sign-in writes a row, and a logged write makes each of those commits wait for a
-- disk flush while holding one of the pool's ten connections. Measured at a 3,000-shopper sale, that alone
-- shed 228-991 shoppers where main shed 8-275 (ADR 0072). The rows are fifteen-minute hints: a database crash
-- empties the table, and an address simply has no credit for one window.
create unlogged table sign_in_successes (
    client_hash  varchar(64) not null,
    pair_hash    varchar(64) not null,
    succeeded_at timestamptz not null default now()
);

create index ix_sign_in_successes_client on sign_in_successes (client_hash, succeeded_at);
create index ix_sign_in_successes_age on sign_in_successes (succeeded_at);
