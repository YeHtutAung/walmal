CREATE TABLE auth_users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    role          VARCHAR(20)  NOT NULL
                  CHECK (role IN ('ADMIN', 'STAFF', 'CASHIER', 'CUSTOMER')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_auth_users_username ON auth_users (username);
CREATE UNIQUE INDEX idx_auth_users_email    ON auth_users (email);
CREATE INDEX        idx_auth_users_role     ON auth_users (role);
CREATE INDEX        idx_auth_users_active   ON auth_users (is_active);

-- Development seed: password is 'admin123' hashed with BCrypt strength 12
INSERT INTO auth_users (id, username, email, password_hash, role, is_active)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'admin',
    'admin@walmal.local',
    '$2a$12$LcCLURJtEKo3MVIyiynmue8LkHVD6VGjN8FHbdnnrxpicweFLMNOa',
    'ADMIN',
    TRUE
);
