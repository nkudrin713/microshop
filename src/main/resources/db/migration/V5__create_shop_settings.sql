CREATE TABLE shop_settings (
    id smallint PRIMARY KEY,
    name text NOT NULL DEFAULT '',
    description text NOT NULL DEFAULT '',
    phone text,
    email text,
    telegram text,
    other_contact text,
    sales_terms text NOT NULL DEFAULT '',
    checkout_contact_rules jsonb NOT NULL
        DEFAULT '{"required":["name"],"any_of":[["phone","email","telegram","other_contact"]]}',
    primary_color text,
    background_color text,
    timezone text NOT NULL DEFAULT 'Europe/Moscow',
    published boolean NOT NULL DEFAULT false,
    sales_enabled boolean NOT NULL DEFAULT false,
    sales_disabled_reason text,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_shop_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_shop_settings_version CHECK (version >= 0),
    CONSTRAINT ck_shop_settings_checkout_rules_object CHECK (jsonb_typeof(checkout_contact_rules) = 'object')
);

INSERT INTO shop_settings (id) VALUES (1);
