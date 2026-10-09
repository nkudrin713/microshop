CREATE TABLE audit_log (
    id uuid PRIMARY KEY,
    actor_type text NOT NULL,
    actor_admin_id uuid,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    before_data jsonb,
    after_data jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('ADMIN', 'SYSTEM')),
    CONSTRAINT ck_audit_log_admin_actor CHECK (actor_type <> 'ADMIN' OR actor_admin_id IS NOT NULL),
    CONSTRAINT fk_audit_log_actor_admin FOREIGN KEY (actor_admin_id) REFERENCES admin_user (id) ON DELETE RESTRICT
);

CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id, created_at, id);
CREATE INDEX ix_audit_log_created_at ON audit_log (created_at, id);
CREATE INDEX ix_audit_log_actor_admin ON audit_log (actor_admin_id);
