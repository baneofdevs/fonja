-- Supports the pending-donation sweep, which asks for donations in one status inside a window of
-- created_at. Without this the sweep is a full table scan every hour, and it runs forever — the
-- number of settled donations only grows, while the number of pending ones stays small.
--
-- status first, created_at second: status is the equality predicate and created_at the range, which
-- is the order that lets InnoDB use both parts of the index rather than just the first.

CREATE INDEX idx_donations_status_created_at ON donations (status, created_at);
