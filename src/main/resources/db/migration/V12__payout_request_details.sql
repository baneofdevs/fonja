-- Columns the payout flow needs that the V6 skeleton did not have.
--
-- payout_requests has never been written to — the module was schema-only until now — so the NOT NULL
-- on merchant_reference is safe to add without a backfill. If that ever stops being true, this needs
-- a three-step add/backfill/alter instead.

ALTER TABLE payout_requests
    -- Our reference, sent to CyberPay as MerchantRef. Unique for the same reason the donation's is:
    -- it is what still identifies a payout when the write storing CyberPay's own reference is lost.
    ADD COLUMN merchant_reference VARCHAR(255) NOT NULL AFTER status,
    -- Which account the money was aimed at, captured at request time. A creator relinking their bank
    -- account must not silently redirect a payout that is already in flight.
    ADD COLUMN bank_account_id BIGINT NULL AFTER creator_id,
    -- Why a FAILED payout failed, for the creator and for whoever has to explain it to them.
    ADD COLUMN failure_reason VARCHAR(500) NULL AFTER gateway_reference,
    -- Stamped before the gateway is called, so a payout gets at most one automatic attempt. A
    -- timed-out instruction may or may not have been accepted, and re-sending it pays twice.
    ADD COLUMN dispatch_attempted_at DATETIME(6) NULL AFTER requested_at,
    ADD CONSTRAINT uk_payout_requests_merchant_reference UNIQUE (merchant_reference),
    ADD CONSTRAINT fk_payout_requests_bank_account
        FOREIGN KEY (bank_account_id) REFERENCES bank_accounts (id);

-- The dispatch job asks for REQUESTED oldest-first every few minutes, and the admin queue asks the
-- same question across two statuses. idx_payout_requests_creator does not help either: it leads on
-- creator_id, and neither query has one.
CREATE INDEX idx_payout_requests_status_requested_at ON payout_requests (status, requested_at);
