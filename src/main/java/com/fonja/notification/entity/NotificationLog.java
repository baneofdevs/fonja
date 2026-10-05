package com.fonja.notification.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One delivery attempt. Append-only: a retry is a new row, never an update, so the history of what
 * was tried is never overwritten by what eventually worked.
 */
@Entity
@Table(name = "notification_logs")
@Getter
public class NotificationLog {

    /** Column width for {@code failure_reason}; longer messages are cut, not rejected. */
    private static final int FAILURE_REASON_MAX = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The address it went to, as it was at the time — the user may change theirs later. */
    @Column private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected NotificationLog() {
        // JPA
    }

    private NotificationLog(
            User user,
            String recipient,
            NotificationType type,
            NotificationChannel channel,
            String subject,
            NotificationStatus status,
            String failureReason) {
        this.user = user;
        this.recipient = recipient;
        this.type = type;
        this.channel = channel;
        this.subject = subject;
        this.status = status;
        this.failureReason =
                failureReason != null && failureReason.length() > FAILURE_REASON_MAX
                        ? failureReason.substring(0, FAILURE_REASON_MAX)
                        : failureReason;
    }

    public static NotificationLog sent(
            User user,
            String recipient,
            NotificationType type,
            NotificationChannel channel,
            String subject) {
        return new NotificationLog(
                user, recipient, type, channel, subject, NotificationStatus.SENT, null);
    }

    public static NotificationLog failed(
            User user,
            String recipient,
            NotificationType type,
            NotificationChannel channel,
            String subject,
            String reason) {
        return new NotificationLog(
                user, recipient, type, channel, subject, NotificationStatus.FAILED, reason);
    }

    public static NotificationLog skipped(
            User user,
            String recipient,
            NotificationType type,
            NotificationChannel channel,
            String subject) {
        return new NotificationLog(
                user,
                recipient,
                type,
                channel,
                subject,
                NotificationStatus.SKIPPED,
                "no delivery channel configured");
    }
}
