-- notification_logs
-- Matches com.fonja.notification.entity.NotificationLog
--
-- Schema only; sending lands alongside the events that trigger it (Days 2-3).

CREATE TABLE notification_logs (
    id      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id BIGINT       NOT NULL,
    type    VARCHAR(255) NOT NULL,
    channel VARCHAR(255) NOT NULL,
    status  VARCHAR(255) NOT NULL,
    sent_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_notification_logs PRIMARY KEY (id),
    CONSTRAINT fk_notification_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_notification_logs_user ON notification_logs (user_id, sent_at);
