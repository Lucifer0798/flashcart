-- The development operator. Applied only under the `demo` profile, which adds classpath:db/seed to
-- the Flyway locations -- so this account cannot reach an environment that did not ask for it by
-- name. Same convention as the catalog's demo rows, and version 900 for the same reason: it leaves
-- 1..899 free for real schema migrations, so a future V4 never has to sort after a seed.
--
-- The hash is BCrypt of 'operator-development-password', generated and then verified against that
-- string rather than pasted from anywhere. DevelopmentOperatorSeedIT checks that it still verifies,
-- so the documented password and the stored hash cannot drift apart silently.
--
-- It exists so `docker compose up` produces a stack whose load and chaos harnesses can seed stock
-- without a manual step. It is a development credential in a public repository and is treated as
-- exactly that: without the profile it is absent, and the user service logs an error at startup if
-- it finds this row while the profile is off.
insert into users (id, email, password_hash, display_name, status, roles)
values (
    '00000000-0000-4000-8000-00000000000f',
    'operator@flashcart.local',
    '$2a$10$Eb8z1Njx/m3DdkF8djGcVuFVF2ZZ6kNdoi8fjuxMy2UII.ogNq7B6',
    'Development Operator',
    'ACTIVE',
    'OPERATOR'
)
on conflict (id) do nothing;
