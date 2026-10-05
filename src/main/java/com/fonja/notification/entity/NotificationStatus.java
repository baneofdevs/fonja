package com.fonja.notification.entity;

public enum NotificationStatus {
    SENT,
    FAILED,
    /**
     * No delivery channel was configured, so nothing was attempted. Logged so the gap is visible.
     */
    SKIPPED
}
