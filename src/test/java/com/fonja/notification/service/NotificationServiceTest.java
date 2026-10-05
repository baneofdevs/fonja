package com.fonja.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.notification.config.NotificationConfig;
import com.fonja.notification.entity.NotificationChannel;
import com.fonja.notification.entity.NotificationLog;
import com.fonja.notification.entity.NotificationStatus;
import com.fonja.notification.entity.NotificationType;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.notification.repository.NotificationLogRepository;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.repository.UserRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The listener's contract: every event produces exactly one log row saying what happened, and
 * nothing that goes wrong on the way out ever reaches the publisher.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long USER_ID = 7L;

    @Mock private EmailSender emailSender;
    @Mock private NotificationLogRepository logRepository;
    @Mock private UserRepository userRepository;

    private NotificationService notificationService;
    private final User user = new User("ada@example.com", "Ada Obi", AuthProvider.GOOGLE, "sub");

    @BeforeEach
    void setUp() {
        NotificationConfig config = new NotificationConfig();
        config.setAppBaseUrl("https://fonja.test");
        notificationService =
                new NotificationService(
                        emailSender, new EmailComposer(config), logRepository, userRepository);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
    }

    private NotificationEvent.DonationReceipt receipt(String email) {
        return new NotificationEvent.DonationReceipt(
                new Recipient(USER_ID, email, "Ada Obi"),
                42L,
                "Surgery for Ada",
                new BigDecimal("5000.00"),
                "NGN");
    }

    @Test
    void sendsAndLogsSent() {
        when(emailSender.isAvailable()).thenReturn(true);

        notificationService.on(receipt("ada@example.com"));

        verify(emailSender)
                .send(
                        eq("ada@example.com"),
                        eq("Ada Obi"),
                        eq("Thank you for giving ₦5,000.00 to Surgery for Ada"),
                        anyString(),
                        anyString());
        NotificationLog entry = savedLog();
        assertThat(entry.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(entry.getType()).isEqualTo(NotificationType.DONATION_RECEIPT);
        assertThat(entry.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(entry.getRecipient()).isEqualTo("ada@example.com");
        assertThat(entry.getSubject()).contains("Surgery for Ada");
        assertThat(entry.getUser()).isSameAs(user);
    }

    @Test
    void aDeliveryFailureIsLoggedWithTheProviderMessageAndSwallowed() {
        when(emailSender.isAvailable()).thenReturn(true);
        doThrow(
                        new EmailSender.EmailDeliveryException(
                                "535-5.7.8 Username and Password not accepted", null))
                .when(emailSender)
                .send(any(), any(), any(), any(), any());

        assertThatCode(() -> notificationService.on(receipt("ada@example.com")))
                .doesNotThrowAnyException();

        NotificationLog entry = savedLog();
        assertThat(entry.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(entry.getFailureReason()).contains("535-5.7.8");
    }

    @Test
    void withoutAConfiguredSenderTheEventIsLoggedAsSkipped() {
        when(emailSender.isAvailable()).thenReturn(false);

        notificationService.on(receipt("ada@example.com"));

        verify(emailSender, never()).send(any(), any(), any(), any(), any());
        assertThat(savedLog().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
    }

    @Test
    void aRecipientWithNoEmailIsLoggedAsFailedWithoutASendAttempt() {
        notificationService.on(receipt(null));

        verify(emailSender, never()).send(any(), any(), any(), any(), any());
        NotificationLog entry = savedLog();
        assertThat(entry.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(entry.getFailureReason()).contains("no email address");
    }

    @Test
    void aLogWriteFailureIsSwallowedToo() {
        when(emailSender.isAvailable()).thenReturn(true);
        when(logRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> notificationService.on(receipt("ada@example.com")))
                .doesNotThrowAnyException();
    }

    private NotificationLog savedLog() {
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        return captor.getValue();
    }
}
