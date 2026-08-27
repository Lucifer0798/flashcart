-- FlashCart catalog schema.
--
-- Scope note: this service owns what a product *is* and what a flash sale *offers*. It deliberately
-- owns no stock counts — how many units are actually left is the inventory service's data, and
-- duplicating it here would create two sources of truth for the one number a flash sale cannot
-- afford to get wrong.

create table categories (
    id          uuid          primary key,
    slug        varchar(120)  not null,
    name        varchar(160)  not null,
    description text,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now(),
    constraint uq_categories_slug unique (slug),
    -- Slugs are normalised to lower case by the application; the check stops a direct SQL insert
    -- from creating a second "Electronics" that differs only by case.
    constraint ck_categories_slug_lower check (slug = lower(slug))
);

create table products (
    id          uuid          primary key,
    sku         varchar(64)   not null,
    slug        varchar(160)  not null,
    name        varchar(200)  not null,
    description text,
    category_id uuid          not null references categories (id),
    base_price  numeric(12,2) not null,
    -- varchar, not char(3): bpchar blank-pads to a fixed width, which makes equality comparisons
    -- against an unpadded 'USD' behave differently depending on how the value arrived.
    currency    varchar(3)    not null default 'USD',
    status      varchar(16)   not null,
    image_url   varchar(500),
    -- JPA optimistic locking. Catalog writes are rare, but two admins editing the same product
    -- during a sale window is exactly the moment a lost update would be expensive.
    version     bigint        not null default 0,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now(),
    constraint uq_products_sku  unique (sku),
    constraint uq_products_slug unique (slug),
    constraint ck_products_base_price check (base_price >= 0),
    constraint ck_products_status     check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    constraint ck_products_sku_upper  check (sku = upper(sku)),
    constraint ck_products_slug_lower check (slug = lower(slug))
);

create index ix_products_category on products (category_id);
-- The storefront listing is "ACTIVE products in this category, newest first"; this index serves it
-- without a sort step.
create index ix_products_status_created on products (status, created_at desc);

create table flash_sales (
    id         uuid         primary key,
    slug       varchar(160) not null,
    name       varchar(200) not null,
    starts_at  timestamptz  not null,
    ends_at    timestamptz  not null,
    -- Lifecycle *intent*, set by an admin: DRAFT, SCHEDULED or CANCELLED.
    --
    -- Whether a sale is live right now is NOT stored. It is derived from the window at read time
    -- (see FlashSale#phase). Storing a live/ended flag would need a scheduler to flip it, and every
    -- second that scheduler is late is a second the storefront sells at the wrong price.
    status     varchar(16)  not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    constraint uq_flash_sales_slug unique (slug),
    constraint ck_flash_sales_window check (ends_at > starts_at),
    constraint ck_flash_sales_status check (status in ('DRAFT', 'SCHEDULED', 'CANCELLED')),
    constraint ck_flash_sales_slug_lower check (slug = lower(slug))
);

-- Serves "which sales are live at this instant", the hottest read on the whole service.
create index ix_flash_sales_window on flash_sales (starts_at, ends_at) where status = 'SCHEDULED';

create table flash_sale_items (
    id                 uuid          primary key,
    flash_sale_id      uuid          not null references flash_sales (id) on delete cascade,
    product_id         uuid          not null references products (id),
    sale_price         numeric(12,2) not null,
    -- How many units this sale is allowed to sell. Catalog states the allocation; the inventory
    -- service is what actually enforces it under contention in Phase 3.
    allocated_units    integer       not null,
    per_customer_limit integer       not null default 1,
    created_at         timestamptz   not null default now(),
    updated_at         timestamptz   not null default now(),
    constraint uq_flash_sale_items_sale_product unique (flash_sale_id, product_id),
    constraint ck_flash_sale_items_price      check (sale_price >= 0),
    constraint ck_flash_sale_items_allocation check (allocated_units > 0),
    constraint ck_flash_sale_items_limit      check (per_customer_limit > 0)
);

-- Resolving one product's effective price walks from the product to its sale items, not the reverse.
create index ix_flash_sale_items_product on flash_sale_items (product_id);
