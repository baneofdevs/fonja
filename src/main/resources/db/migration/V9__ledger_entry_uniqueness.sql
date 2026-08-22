-- Makes a double post to the ledger impossible at the database level.
--
-- LedgerService already checks before writing, but that check and the insert are two statements:
-- two concurrent CyberPay webhook retries for the same donation can both pass the check and both
-- write, crediting a creator twice. These indexes close that window — the second insert fails
-- rather than succeeding.
--
-- Each entry type may appear at most once per donation or payout request, which is exactly the
-- accounting rule: one credit and one fee per donation, one debit, one fee and at most one reversal
-- of each per withdrawal.
--
-- MySQL permits repeated NULLs in a unique index, so donation entries (payout_request_id NULL) and
-- withdrawal entries (donation_id NULL) do not collide with each other.

CREATE UNIQUE INDEX uk_ledger_entries_donation_type
    ON ledger_entries (donation_id, entry_type);

CREATE UNIQUE INDEX uk_ledger_entries_payout_request_type
    ON ledger_entries (payout_request_id, entry_type);

-- idx_ledger_entries_donation is now redundant: the unique index above has donation_id as its
-- leftmost column and serves the same lookups.
DROP INDEX idx_ledger_entries_donation ON ledger_entries;
