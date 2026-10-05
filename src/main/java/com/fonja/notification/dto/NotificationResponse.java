package com.fonja.notification.dto;

import com.fonja.notification.entity.NotificationType;
import com.fonja.notification.entity.UserNotification;
import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        String link,
        boolean read,
        Instant createdAt) {

    public static NotificationResponse from(UserNotification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getLink(),
                n.isRead(),
                n.getCreatedAt());
    }
}
