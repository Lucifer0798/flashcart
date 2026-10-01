-- One account per narrow role, so something actually runs without OPERATOR.
--
-- ADR 0038 split OPERATOR into WAREHOUSE, CATALOG and SUPPORT and left the split unexercised: every
-- harness still signed in as the one account that holds OPERATOR, which satisfies every role. A rule
-- scoped to the wrong role would have been masked by that superset and nobody would have known.
--
-- These exist so CI and the chaos harness can hold exactly the privileges their work needs, which is
-- the only arrangement in which a mis-scoped rule fails loudly. See ADR 0039.
--
-- Same password as the development operator, and the same published hash: this is one checked-in
-- credential wearing four hats rather than four secrets to keep track of. It is acceptable here for
-- the reason ADR 0024 gives and for no other -- these rows exist only under the `demo` profile, and
-- SeededOperatorCheck shouts if they are found anywhere else.
insert into users (id, email, password_hash, display_name, status, roles)
values
    ('00000000-0000-4000-8000-00000000000a', 'warehouse@flashcart.local',
     '$2a$10$Eb8z1Njx/m3DdkF8djGcVuFVF2ZZ6kNdoi8fjuxMy2UII.ogNq7B6',
     'Development Warehouse', 'ACTIVE', 'WAREHOUSE'),
    ('00000000-0000-4000-8000-00000000000b', 'catalog@flashcart.local',
     '$2a$10$Eb8z1Njx/m3DdkF8djGcVuFVF2ZZ6kNdoi8fjuxMy2UII.ogNq7B6',
     'Development Catalogue', 'ACTIVE', 'CATALOG'),
    ('00000000-0000-4000-8000-00000000000c', 'support@flashcart.local',
     '$2a$10$Eb8z1Njx/m3DdkF8djGcVuFVF2ZZ6kNdoi8fjuxMy2UII.ogNq7B6',
     'Development Support', 'ACTIVE', 'SUPPORT')
on conflict (id) do nothing;
