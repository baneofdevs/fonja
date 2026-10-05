-- user_notifications
-- Matches com.fonja.notification.entity.UserNotification
--
-- The in-app inbox behind the bell. Separate from notification_log, which records email delivery
-- attempts: this is what the user reads, that is what ops reads.

CREATE TABLE user_notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(255) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    link       VARCHAR(512) NULL,
    read_at    DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_user_notifications PRIMARY KEY (id),
    CONSTRAINT fk_user_notifications_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

-- The inbox page (newest first) and the unread count both lead with user_id.
CREATE INDEX idx_user_notifications_inbox ON user_notifications (user_id, created_at);
CREATE INDEX idx_user_notifications_unread ON user_notifications (user_id, read_at);
