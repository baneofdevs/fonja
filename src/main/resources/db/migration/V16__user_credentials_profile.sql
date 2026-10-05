-- users: names split, password sign-up, email verification, onboarding, profile
-- user_interests, auth_tokens
-- Matches com.fonja.user.entity.User, AuthToken

-- 1. First and last name replace full_name. The full name is derived from them in the entity, so
--    every existing reader of getFullName() is unchanged. Existing rows split at the first space;
--    a single-word name keeps it all as the first name.
ALTER TABLE users
    ADD COLUMN first_name VARCHAR(100) NULL AFTER email,
    ADD COLUMN last_name  VARCHAR(100) NULL AFTER first_name;

UPDATE users
SET first_name = LEFT(TRIM(SUBSTRING_INDEX(TRIM(full_name), ' ', 1)), 100),
    last_name  = LEFT(CASE
                          WHEN LOCATE(' ', TRIM(full_name)) = 0 THEN ''
                          ELSE TRIM(SUBSTRING(TRIM(full_name), LOCATE(' ', TRIM(full_name)) + 1))
                      END, 100);

ALTER TABLE users
    MODIFY COLUMN first_name VARCHAR(100) NOT NULL,
    MODIFY COLUMN last_name  VARCHAR(100) NOT NULL,
    DROP COLUMN full_name;

-- 2. Password sign-up and email verification. Nobody could sign up without an OAuth provider
--    before this, and those providers vouch for the address, so every existing row is verified.
--    `verified` (creator KYC) is a different thing and is untouched.
ALTER TABLE users
    ADD COLUMN password_hash  VARCHAR(100) NULL AFTER auth_provider_sub,
    ADD COLUMN email_verified BIT(1)       NOT NULL DEFAULT b'0' AFTER password_hash;

UPDATE users SET email_verified = b'1';

-- 3. Onboarding and profile. Existing users are treated as already onboarded so the new
--    "how will you use Fonja" step does not ambush people who have been using the app.
ALTER TABLE users
    ADD COLUMN account_intent  VARCHAR(20)  NULL,
    ADD COLUMN onboarded_at    DATETIME(6)  NULL,
    ADD COLUMN avatar_media_id BIGINT       NULL,
    ADD COLUMN bio             VARCHAR(500) NULL,
    ADD COLUMN location        VARCHAR(120) NULL,
    ADD CONSTRAINT fk_users_avatar_media FOREIGN KEY (avatar_media_id) REFERENCES campaign_media (id);

UPDATE users SET onboarded_at = created_at;

CREATE TABLE user_interests (
    user_id     BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    CONSTRAINT pk_user_interests PRIMARY KEY (user_id, category_id),
    CONSTRAINT fk_user_interests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_interests_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE = InnoDB;

-- 4. One-time secrets: six-digit email codes and password-reset links. Only a SHA-256 of the
--    secret is stored, so a leaked table cannot be replayed.
CREATE TABLE auth_tokens (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    purpose     VARCHAR(30) NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    consumed_at DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_auth_tokens PRIMARY KEY (id),
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_auth_tokens_user_purpose ON auth_tokens (user_id, purpose, created_at);
CREATE INDEX idx_auth_tokens_hash ON auth_tokens (token_hash);
