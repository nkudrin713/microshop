CREATE TABLE installation_state (
    id smallint PRIMARY KEY,
    status text NOT NULL,
    setup_token_hash bytea,
    app_secret_hash bytea NOT NULL,
    owner_admin_id uuid REFERENCES admin_user(id) ON DELETE RESTRICT,
    initialized_at timestamptz,
    CONSTRAINT ck_installation_singleton CHECK (id = 1),
    CONSTRAINT ck_installation_app_secret_hash CHECK (octet_length(app_secret_hash) = 32),
    CONSTRAINT ck_installation_state CHECK (
        (status = 'UNINITIALIZED' AND setup_token_hash IS NOT NULL
            AND octet_length(setup_token_hash) = 32 AND owner_admin_id IS NULL AND initialized_at IS NULL)
        OR
        (status = 'INITIALIZED' AND setup_token_hash IS NULL
            AND owner_admin_id IS NOT NULL AND initialized_at IS NOT NULL)
    )
);
