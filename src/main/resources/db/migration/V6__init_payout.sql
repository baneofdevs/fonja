-- bank_accounts, payout_requests
-- Matches com.fonja.payout.entity.*
--
-- Schema only; the withdrawal flow lands on Day 3.

CREATE TABLE bank_accounts (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    account_name   VARCHAR(255) NOT NULL,
    account_number VARCHAR(255) NOT NULL,
    bank_code      VARCHAR(255) NOT NULL,
    bank_name      VARCHAR(255) NOT NULL,
    -- Set when the account name is matched against verification (spec §4.6).
    verified_at    DATETIME(6)  NULL,
    CONSTRAINT pk_bank_accounts PRIMARY KEY (id),
    CONSTRAINT uk_bank_accounts_user UNIQUE (user_id),
    CONSTRAINT fk_bank_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE payout_requests (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    creator_id        BIGINT         NOT NULL,
    -- Requested amount, before the flat withdrawal fee.
    amount            DECIMAL(14, 2) NOT NULL,
    -- Flat 200 NGN, per BRD.
    fee               DECIMAL(14, 2) NOT NULL,
    status            VARCHAR(255)   NOT NULL,
    -- Null until CyberPay assigns one.
    gateway_reference VARCHAR(255)   NULL,
    requested_at      DATETIME(6)    NOT NULL,
    completed_at      DATETIME(6)    NULL,
    CONSTRAINT pk_payout_requests PRIMARY KEY (id),
    CONSTRAINT uk_payout_requests_gateway_reference UNIQUE (gateway_reference),
    CONSTRAINT fk_payout_requests_creator FOREIGN KEY (creator_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_payout_requests_creator ON payout_requests (creator_id, status);

-- Deferred from V5 — payout_requests had to exist first.
ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_entries_payout_request
        FOREIGN KEY (payout_request_id) REFERENCES payout_requests (id);
