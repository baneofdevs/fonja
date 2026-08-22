-- Campaign review feedback + fundraising deadline.
--
-- rejection_reason: written by an admin rejecting a pending campaign, cleared on
-- resubmission. Returned to the creator so they know what to fix.
-- deadline: the date the campaign stops accepting donations. NULL means open-ended;
-- a passed deadline on an ACTIVE campaign is what moves it to EXPIRED.

ALTER TABLE campaigns
    ADD COLUMN rejection_reason VARCHAR(255) NULL AFTER lifecycle_state,
    ADD COLUMN deadline         DATETIME(6)  NULL AFTER approved_at;

-- The expiry sweep scans ACTIVE campaigns whose deadline has passed.
CREATE INDEX idx_campaigns_deadline ON campaigns (lifecycle_state, deadline);
