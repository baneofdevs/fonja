package com.fonja.notification.service;

import com.fonja.common.exception.ApiException;
import com.fonja.notification.dto.NotificationResponse;
import com.fonja.notification.entity.UserNotification;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.repository.UserNotificationRepository;
import com.fonja.user.repository.UserRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The in-app inbox. Listens to the same {@link NotificationEvent}s as {@link NotificationService} —
 * after commit, off the request thread, never throwing back — and files one entry per event.
 *
 * <p>Independent of email on purpose: SMTP being down or unconfigured loses no in-app
 * notifications, and an inbox write failing sends no fewer emails.
 */
@Service
public class InAppNotificationService {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationService.class);

    private final UserNotificationRepository repository;
    private final UserRepository userRepository;
    private final InAppComposer composer;

    public InAppNotificationService(
            UserNotificationRepository repository,
            UserRepository userRepository,
            InAppComposer composer) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.composer = composer;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(NotificationEvent event) {
        try {
            composer.compose(event)
                    .ifPresent(
                            m ->
                                    repository.save(
                                            new UserNotification(
                                                    userRepository.getReferenceById(
                                                            event.recipient().userId()),
                                                    event.type(),
                                                    truncate(m.title(), 200),
                                                    truncate(m.body(), 500),
                                                    m.link())));
        } catch (RuntimeException e) {
            log.error(
                    "Could not file in-app notification {} for user id={}: {}",
                    event.type(),
                    event.recipient().userId(),
                    e.getMessage(),
                    e);
        }
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(Long userId, boolean unreadOnly, Pageable pageable) {
        Page<UserNotification> page =
                unreadOnly
                        ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
                                userId, pageable)
                        : repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return page.map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public NotificationResponse markRead(Long userId, Long notificationId) {
        UserNotification n =
                repository
                        .findByIdAndUserId(notificationId, userId)
                        .orElseThrow(() -> ApiException.notFound("Notification not found"));
        n.markRead(Instant.now());
        return NotificationResponse.from(n);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId, Instant.now());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
