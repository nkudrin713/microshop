CREATE TABLE admin_user (
    id uuid PRIMARY KEY,
    login text NOT NULL,
    password_hash text NOT NULL,
    display_name text NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_admin_user_login UNIQUE (login)
);
