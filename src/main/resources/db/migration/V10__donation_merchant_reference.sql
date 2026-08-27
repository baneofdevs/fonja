-- Splits a donation's one reference into the two that actually exist.
--
-- V4 had a single gateway_reference, NOT NULL — written on the assumption that a donation row
-- could be created with CyberPay's reference already in hand. It cannot. CyberPay only issues
-- theirs in the response to POST /api/v1/payments, and the row has to exist before that call so
-- that a payment can never be initiated against nothing.
--
-- So there are two:
--   merchant_reference  ours, generated before the call, NOT NULL and permanent
--   gateway_reference   theirs, NULL until the initiate response comes back
--
-- This is what makes the initiate path safe to interrupt. If the call reaches CyberPay but our
-- response handling dies before storing their reference, the row is still on disk under our own
-- reference — and CyberPay echoes it back as MerchantRef on every webhook, so the payment can
-- still be settled. Without the split, that donor's money would arrive against a row we never
-- managed to write.

ALTER TABLE donations ADD COLUMN merchant_reference VARCHAR(64) NULL AFTER donor_id;

-- Rows written before this migration only ever had the one reference. Copying it across keeps
-- them resolvable by either name.
UPDATE donations SET merchant_reference = gateway_reference WHERE merchant_reference IS NULL;

ALTER TABLE donations MODIFY COLUMN merchant_reference VARCHAR(64) NOT NULL;

-- Relaxed from NOT NULL. uk_donations_gateway_reference stays: MySQL lets a unique index hold any
-- number of NULLs, so pending donations do not collide with each other while they wait for a
-- reference, and a settled one still cannot be recorded twice.
ALTER TABLE donations MODIFY COLUMN gateway_reference VARCHAR(255) NULL;

-- Unique for the same reason gateway_reference is: it is the other half of the idempotency guard,
-- and the only handle we have on a payment CyberPay has not answered for yet.
ALTER TABLE donations ADD CONSTRAINT uk_donations_merchant_reference UNIQUE (merchant_reference);
