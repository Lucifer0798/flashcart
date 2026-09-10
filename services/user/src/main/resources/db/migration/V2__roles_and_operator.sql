-- Roles, and the one account that has one.
--
-- Until now every signed-in caller was a shopper, which was fine while the only authenticated
-- operations were placing and reading your own orders. It stops being fine the moment receiving five
-- thousand units of stock or marking a parcel delivered requires a token: being signed in makes
-- somebody a customer, not a warehouse.

-- A comma-separated list rather than a join table. There is exactly one role that means anything and
-- a user has none or that one; a roles table, a user_roles table and two more joins on the sign-in
-- path would be modelling a permission system this platform does not have. When a second role earns
-- its place, that is the migration to write.
alter table users add column roles varchar(200) not null default '';

comment on column users.roles is
    'Comma-separated. Empty for a shopper; OPERATOR for back-office access to inventory, payment and shipping.';

-- The seeded operator.
--
-- The hash below is BCrypt of 'operator-development-password', generated and then verified against
-- that string rather than pasted from anywhere. It exists so `docker compose up` produces a stack
-- whose load and chaos harnesses can seed stock without a manual step.
--
-- It is a development credential in a public repository and must be treated as exactly that: a
-- deployment that keeps this row has no operator security at all. That is stated here, in the file
-- that creates it, because a seeded credential nobody remembers is worse than no credential.
--
-- Rotating it is a single UPDATE, which is why the account is seeded rather than the password being
-- read from an environment variable at startup -- a service that rewrites its own credentials on
-- every boot is harder to reason about than a row somebody can change.
insert into users (id, email, password_hash, display_name, status, roles)
values (
    '00000000-0000-4000-8000-00000000000f',
    'operator@flashcart.local',
    '$2a$10$Eb8z1Njx/m3DdkF8djGcVuFVF2ZZ6kNdoi8fjuxMy2UII.ogNq7B6',
    'Development Operator',
    'ACTIVE',
    'OPERATOR'
);
