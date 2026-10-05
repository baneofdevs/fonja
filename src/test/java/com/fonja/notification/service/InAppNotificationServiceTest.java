package com.fonja.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.common.exception.ApiException;
import com.fonja.notification.entity.NotificationType;
import com.fonja.notification.entity.UserNotification;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.notification.repository.UserNotificationRepository;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceTest {

    private static final Recipient ADA = new Recipient(7L, "ada@example.com", "Ada Obi");

    @Mock private UserNotificationRepository repository;
    @Mock private UserRepository userRepository;

    private final InAppComposer composer = new InAppComposer();
    private InAppNotificationService service;
    private final User user = new User("ada@example.com", "Ada Obi", AuthProvider.GOOGLE, "s");

    @BeforeEach
    void setUp() {
        service = new InAppNotificationService(repository, userRepository, composer);
    }

    @Test
    void anApprovalLandsInTheInboxLinkingToTheCelebration() {
        when(userRepository.getReferenceById(7L)).thenReturn(user);

        service.on(new NotificationEvent.CampaignReviewed(ADA, 42L, "Help Ada", true, null));

        ArgumentCaptor<UserNotification> saved = ArgumentCaptor.forClass(UserNotification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.CAMPAIGN_APPROVED);
        assertThat(saved.getValue().getLink()).isEqualTo("/dashboard/campaigns?celebrate=42");
        assertThat(saved.getValue().isRead()).isFalse();
    }

    @Test
    void signUpCodesAndResetLinksNeverReachTheInbox() {
        service.on(new NotificationEvent.EmailVerificationCode(ADA, "123456", 15));
        service.on(new NotificationEvent.PasswordResetLink(ADA, "https://x/reset?token=t", 30));

        verify(repository, never()).save(any());
    }

    @Test
    void aFailedWriteIsSwallowed() {
        when(userRepository.getReferenceById(7L)).thenReturn(user);
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(
                        () ->
                                service.on(
                                        new NotificationEvent.DonationReceipt(
                                                ADA, 1L, "Help Ada", BigDecimal.TEN, "NGN")))
                .doesNotThrowAnyException();
    }

    @Test
    void someoneElsesNotificationReadsAsNotFound() {
        when(repository.findByIdAndUserId(5L, 8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(8L, 5L)).isInstanceOf(ApiException.class);
    }

    @Test
    void everyNonAuthEventHasATitleABodyAndAnAppLink() {
        BigDecimal amount = new BigDecimal("5000");
        NotificationEvent[] events = {
            new NotificationEvent.DonationReceipt(ADA, 1L, "T", amount, "NGN"),
            new NotificationEvent.DonationReceived(ADA, 1L, "T", null, amount, "NGN"),
            new NotificationEvent.CampaignReviewed(ADA, 1L, "T", false, "Blurry photo"),
            new NotificationEvent.CampaignCompleted(ADA, 1L, "T", amount, amount, "NGN"),
            new NotificationEvent.VerificationReviewed(ADA, true, null),
            new NotificationEvent.VerificationReviewed(ADA, false, null),
            new NotificationEvent.WithdrawalRequested(ADA, 1L, amount, amount, "NGN", "B", "*1"),
            new NotificationEvent.WithdrawalSettled(
                    ADA, 1L, amount, amount, "NGN", "B", "*1", true, null),
            new NotificationEvent.WithdrawalSettled(
                    ADA, 1L, amount, amount, "NGN", "B", "*1", false, "x"),
            new NotificationEvent.CampaignCommentPosted(ADA, 1L, "T", 2L, "Tunde", "Nice"),
        };
        for (NotificationEvent event : events) {
            InAppComposer.InApp m = composer.compose(event).orElseThrow();
            assertThat(m.title()).isNotBlank();
            assertThat(m.body()).isNotBlank().doesNotContain("null");
            assertThat(m.link()).startsWith("/");
        }
    }
}
