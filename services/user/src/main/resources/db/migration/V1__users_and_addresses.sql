-- The user service, finally.
--
-- Marked "Phase 4" in the README since Phase 4 and never built, because every phase after it had
-- something more load-bearing to prove. In the meantime `customerId` was an opaque string the client
-- supplied in the request body, which meant anyone could place an order as anyone and cancel a
-- stranger's. This schema, and the token issued from it, is what makes that stop being true.

create table users (
    id            uuid         primary key,
    -- Citext would be tidier, but it is an extension and this platform has kept its schema to plain
    -- PostgreSQL throughout. Email is stored already-lowercased by the service instead, so the
    -- unique constraint means what a reader expects it to mean.
    email         varchar(320) not null,
    -- BCrypt, and the column is sized for it: 60 characters today, with room for a longer hash if
    -- the cost factor or algorithm ever changes. Never the password.
    password_hash varchar(100) not null,
    display_name  varchar(120) not null,

    -- A user is disabled rather than deleted. An order references its customer for as long as the
    -- order is worth keeping, and orders outlive accounts.
    status        varchar(20)  not null default 'ACTIVE',

    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),

    constraint uq_users_email unique (email),
    constraint ck_users_status check (status in ('ACTIVE', 'DISABLED'))
);

-- Addresses belong to a user and are ordinary mutable data: no history, no soft delete. Shipping
-- copies what it needs onto the consignment at the moment it is created, so editing an address here
-- cannot rewrite where a parcel was already sent.
create table addresses (
    id           uuid         primary key,
    user_id      uuid         not null references users (id) on delete cascade,

    label        varchar(60),
    line1        varchar(200) not null,
    line2        varchar(200),
    city         varchar(120) not null,
    postcode     varchar(20)  not null,
    country      varchar(2)   not null,

    -- Exactly one default per user, enforced below rather than by application code remembering to
    -- clear the old one.
    is_default   boolean      not null default false,

    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),

    constraint ck_addresses_country check (country ~ '^[A-Z]{2}$')
);

create index ix_addresses_user on addresses (user_id);

-- A partial unique index, which is how "at most one default" is said in PostgreSQL. The alternative
-- -- a default_address_id column on users -- lets the two sides disagree, and something eventually
-- does.
create unique index uq_addresses_one_default_per_user
    on addresses (user_id) where is_default;
