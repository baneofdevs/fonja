package com.fonja.notification.service;

import com.fonja.notification.entity.NotificationChannel;
import com.fonja.notification.entity.NotificationLog;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.notification.repository.NotificationLogRepository;
import com.fonja.user.entity.User;
import com.fonja.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers {@link NotificationEvent}s and records every attempt.
 *
 * <p>Runs after the publishing transaction commits, on the async executor. Both halves matter.
 * After commit, because an email about a donation that then rolled back is a lie; async, because an
 * SMTP round-trip is a second or more and none of the callers — a webhook CyberPay is waiting on,
 * an admin clicking approve — should pay it. {@code fallbackExecution} covers the one case where a
 * publisher is not transactional: the event still goes out rather than vanishing.
 *
 * <p>Nothing here ever throws back to the publisher. A failed send is a FAILED row in the log and a
 * warning, and the domain change it described stands.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final EmailSender emailSender;
    private final EmailComposer composer;
    private final NotificationLogRepository logRepository;
    private final UserRepository userRepository;

    public NotificationService(
            EmailSender emailSender,
            EmailComposer composer,
            NotificationLogRepository logRepository,
            UserRepository userRepository) {
        this.emailSender = emailSender;
        this.composer = composer;
        this.logRepository = logRepository;
        this.userRepository = userRepository;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(NotificationEvent event) {
        Recipient to = event.recipient();
        EmailComposer.Email email = composer.compose(event);
        // A reference, not a load: the log row only needs the foreign key.
        User user = userRepository.getReferenceById(to.userId());

        if (to.email() == null || to.email().isBlank()) {
            record(
                    NotificationLog.failed(
                            user,
                            null,
                            event.type(),
                            NotificationChannel.EMAIL,
                            email.subject(),
                            "recipient has no email address"));
            return;
        }

        if (!emailSender.isAvailable()) {
            log.debug("Email not configured; {} to user id={} skipped", event.type(), to.userId());
            record(
                    NotificationLog.skipped(
                            user,
                            to.email(),
                            event.type(),
                            NotificationChannel.EMAIL,
                            email.subject()));
            return;
        }

        try {
            emailSender.send(to.email(), to.name(), email.subject(), email.body(), email.html());
            log.info("Sent {} to user id={}", event.type(), to.userId());
            record(
                    NotificationLog.sent(
                            user,
                            to.email(),
                            event.type(),
                            NotificationChannel.EMAIL,
                            email.subject()));
        } catch (RuntimeException e) {
            // Deliberately broad. Whatever went wrong belongs in the log row, and the publisher
            // must never hear about it.
            log.warn(
                    "Failed to send {} to user id={}: {}",
                    event.type(),
                    to.userId(),
                    e.getMessage());
            record(
                    NotificationLog.failed(
                            user,
                            to.email(),
                            event.type(),
                            NotificationChannel.EMAIL,
                            email.subject(),
                            e.getMessage()));
        }
    }

    private void record(NotificationLog entry) {
        try {
            logRepository.save(entry);
        } catch (RuntimeException e) {
            // The log is the last thing standing between "sent" and "no idea". If even that fails,
            // say so loudly — but on this thread, where nobody is waiting.
            log.error("Could not write notification log entry: {}", e.getMessage(), e);
        }
    }
}
