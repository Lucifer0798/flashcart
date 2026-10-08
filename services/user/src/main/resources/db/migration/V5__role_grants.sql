-- ADR 0047: staff roles that can only be the ones that exist, granted on the record.
--
-- ADR 0038 split OPERATOR into WAREHOUSE, CATALOG and SUPPORT, and ADR 0022 chose a deliberate UPDATE
-- against users.roles as the way to grant one. Nothing checked what that UPDATE wrote. A typo --
-- 'WAREHOSUE' -- was stored, issued in every token, and matched no rule anywhere: the account simply
-- got 403s, with nothing pointing at why. And nothing recorded who granted what.

-- Only known roles, comma-separated, no spaces, no repeats of the separator. The canonical form the
-- grant script writes; the parser stays tolerant of spaces for anything older.
alter table users add constraint ck_users_roles_known check (
    roles ~ '^((OPERATOR|WAREHOUSE|CATALOG|SUPPORT)(,(OPERATOR|WAREHOUSE|CATALOG|SUPPORT))*)?$'
);

-- Every grant and revoke, and why. Written by scripts/grant-role.sh in the same transaction as the
-- change to users.roles, so the two cannot disagree. Append-only by convention: nothing in the
-- application writes or deletes here.
create table role_grants (
    id          bigserial    primary key,
    user_id     uuid         not null references users (id),
    role        varchar(16)  not null,
    action      varchar(8)   not null,
    -- Mandatory for every change, and for OPERATOR it is the break-glass justification.
    reason      varchar(300) not null,
    -- The database session's user and the operating-system user who ran the script, as far as either
    -- can be known. Not authentication -- whoever can run this already has the database -- but the
    -- difference between "somebody granted OPERATOR" and a name to ask.
    granted_by  varchar(100) not null,
    granted_at  timestamptz  not null default now(),
    constraint ck_role_grants_role check (role in ('OPERATOR', 'WAREHOUSE', 'CATALOG', 'SUPPORT')),
    constraint ck_role_grants_action check (action in ('GRANT', 'REVOKE')),
    constraint ck_role_grants_reason check (length(trim(reason)) > 0)
);

create index ix_role_grants_user on role_grants (user_id, granted_at desc);
