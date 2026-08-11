-- users, user_roles
-- Matches com.fonja.user.entity.User + Role

CREATE TABLE users (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(255) NOT NULL,
    full_name         VARCHAR(255) NOT NULL,
    phone             VARCHAR(32)  NULL,
    auth_provider     VARCHAR(255) NOT NULL,
    -- Provider `sub` claim. Null for EMAIL accounts, so the unique index is
    -- effectively "unique among OAuth accounts" (MySQL allows repeated NULLs).
    auth_provider_sub VARCHAR(255) NULL,
    verified          BIT(1)       NOT NULL DEFAULT b'0',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_auth_provider_sub UNIQUE (auth_provider_sub)
) ENGINE = InnoDB;

CREATE TABLE user_roles (
    user_id BIGINT       NOT NULL,
    role    VARCHAR(255) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;
