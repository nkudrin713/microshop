-- Deliberately invalid migration after the isolated test baseline.
CREATE TABLE migration_failure_probe (id integer PRIMARY KEY);
INSERT INTO migration_failure_probe (missing_column) VALUES (1);
