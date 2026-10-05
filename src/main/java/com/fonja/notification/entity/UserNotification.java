package com.fonja.notification.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/** One entry in a user's in-app inbox — what the bell counts and the notifications page lists. */
@Entity
@Table(name = "user_notifications")
@Getter
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationType type;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(nullable = false, length = 500, updatable = false)
    private String body;

    /** An in-app path to open when the notification is clicked, e.g. {@code /campaigns/42}. */
    @Column(length = 512, updatable = false)
    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserNotification() {
        // JPA
    }

    public UserNotification(
            User user, NotificationType type, String title, String body, String link) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.body = body;
        this.link = link;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }
}
