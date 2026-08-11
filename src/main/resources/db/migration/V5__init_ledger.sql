-- ledger_entries
-- Matches com.fonja.ledger.entity.LedgerEntry
-- append-only: no update/delete grants beyond the app's own service account in prod
--
-- Schema only; the split-write path lands on Day 2.
--
-- There is deliberately no `balance` column anywhere in this schema. Available
-- earnings are always derived by summing entries. See technical spec §5.2.

CREATE TABLE ledger_entries (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    -- Null beneficiary = Fonja platform revenue entry.
    beneficiary_id    BIGINT         NULL,
    donation_id       BIGINT         NULL,
    payout_request_id BIGINT         NULL,
    entry_type        VARCHAR(255)   NOT NULL,
    -- Always positive; direction is implied by entry_type, not by sign.
    amount            DECIMAL(14, 2) NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_beneficiary FOREIGN KEY (beneficiary_id) REFERENCES users (id),
    CONSTRAINT fk_ledger_entries_donation FOREIGN KEY (donation_id) REFERENCES donations (id)
    -- fk_ledger_entries_payout_request is added in V6: payout_requests does not
    -- exist yet at this point in the migration order.
) ENGINE = InnoDB;

-- Balance derivation scans a creator's entries; this index is what keeps that cheap.
CREATE INDEX idx_ledger_entries_beneficiary ON ledger_entries (beneficiary_id, entry_type);
CREATE INDEX idx_ledger_entries_donation ON ledger_entries (donation_id);
