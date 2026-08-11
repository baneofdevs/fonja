-- donations
-- Matches com.fonja.donation.entity.Donation
-- gateway_reference UNIQUE constraint is the idempotency guard
--
-- Schema only; the CyberPay integration lands on Day 3.

CREATE TABLE donations (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    campaign_id       BIGINT         NOT NULL,
    donor_id          BIGINT         NOT NULL,
    -- Gross, before the 7% platform fee split.
    amount            DECIMAL(14, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    payment_method    VARCHAR(255)   NULL,
    gateway_reference VARCHAR(255)   NOT NULL,
    status            VARCHAR(255)   NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    CONSTRAINT pk_donations PRIMARY KEY (id),
    -- Non-negotiable: a CyberPay webhook retry must not double-process.
    -- See technical spec §5.1.
    CONSTRAINT uk_donations_gateway_reference UNIQUE (gateway_reference),
    CONSTRAINT fk_donations_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id),
    CONSTRAINT fk_donations_donor FOREIGN KEY (donor_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_donations_campaign ON donations (campaign_id);
CREATE INDEX idx_donations_donor ON donations (donor_id);
