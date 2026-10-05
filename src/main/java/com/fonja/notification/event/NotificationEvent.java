package com.fonja.notification.event;

import com.fonja.notification.entity.NotificationType;
import java.math.BigDecimal;

/**
 * Something happened that a user should hear about (BRD §13).
 *
 * <p>Domain services publish these from inside their transaction and carry on; {@code
 * NotificationService} picks them up after the commit, on another thread, and decides how to
 * deliver. That split is the point: a donation is settled whether or not Gmail is reachable, and
 * CyberPay's webhook gets its 2xx without waiting on an SMTP handshake.
 *
 * <p>Every event carries the facts its message needs as plain values — see {@link Recipient} for
 * why nothing here is an entity.
 */
public sealed interface NotificationEvent {

    Recipient recipient();

    NotificationType type();

    /** To the donor: thanks, here is what you gave and to whom. */
    record DonationReceipt(
            Recipient recipient,
            Long campaignId,
            String campaignTitle,
            BigDecimal amount,
            String currency)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return NotificationType.DONATION_RECEIPT;
        }
    }

    /** To the creator: someone just gave to your campaign. */
    record DonationReceived(
            Recipient recipient,
            Long campaignId,
            String campaignTitle,
            String donorName,
            BigDecimal amount,
            String currency)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return NotificationType.DONATION_RECEIVED;
        }
    }

    /**
     * To the creator: an admin decided on the campaign. {@code reason} is set only on rejection.
     */
    record CampaignReviewed(
            Recipient recipient,
            Long campaignId,
            String campaignTitle,
            boolean approved,
            String reason)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return approved
                    ? NotificationType.CAMPAIGN_APPROVED
                    : NotificationType.CAMPAIGN_REJECTED;
        }
    }

    /** To the creator: the goal was reached and the campaign has closed. */
    record CampaignCompleted(
            Recipient recipient,
            Long campaignId,
            String campaignTitle,
            BigDecimal raisedAmount,
            BigDecimal goalAmount,
            String currency)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return NotificationType.CAMPAIGN_COMPLETED;
        }
    }

    /** To the applicant: an admin decided on their identity verification. */
    record VerificationReviewed(Recipient recipient, boolean approved, String reason)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return approved
                    ? NotificationType.VERIFICATION_APPROVED
                    : NotificationType.VERIFICATION_REJECTED;
        }
    }

    /** To the creator: we have your withdrawal and this much has been set aside for it. */
    record WithdrawalRequested(
            Recipient recipient,
            Long payoutId,
            BigDecimal amount,
            BigDecimal fee,
            String currency,
            String bankName,
            String maskedAccountNumber)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return NotificationType.WITHDRAWAL_REQUESTED;
        }
    }

    /**
     * To the creator: the withdrawal either landed or bounced. On failure the amount and fee are
     * already back in their balance by the time this is sent — the refund and the status change are
     * one write.
     */
    record WithdrawalSettled(
            Recipient recipient,
            Long payoutId,
            BigDecimal amount,
            BigDecimal fee,
            String currency,
            String bankName,
            String maskedAccountNumber,
            boolean paid,
            String failureReason)
            implements NotificationEvent {
        @Override
        public NotificationType type() {
            return paid
                    ? NotificationType.WITHDRAWAL_CONFIRMED
                    : NotificationType.WITHDRAWAL_FAILED;
        }
    }
}
