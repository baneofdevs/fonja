-- What was sent, to whom, and why it failed.
--
-- The original table recorded only that a notification of some type went to some user. That is
-- not enough to answer the two questions ops actually asks — "did the creator get the payout
-- email?" and "why not?" — so the row now carries the address it went to, the subject line, and
-- the failure reason when there is one. Bodies are not stored: they are reproducible from the
-- type and the domain record, and they contain amounts and names that do not need a second home.

ALTER TABLE notification_logs
    ADD COLUMN recipient      VARCHAR(255) NULL AFTER user_id,
    ADD COLUMN subject        VARCHAR(255) NULL AFTER channel,
    ADD COLUMN failure_reason VARCHAR(512) NULL AFTER status;

-- Ops looks up failures, not successes.
CREATE INDEX idx_notification_logs_status ON notification_logs (status, sent_at);
