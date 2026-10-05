package com.fonja.notification.service;

import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.NotificationEvent.CampaignCommentPosted;
import com.fonja.notification.event.NotificationEvent.CampaignCompleted;
import com.fonja.notification.event.NotificationEvent.CampaignReviewed;
import com.fonja.notification.event.NotificationEvent.DonationReceipt;
import com.fonja.notification.event.NotificationEvent.DonationReceived;
import com.fonja.notification.event.NotificationEvent.EmailVerificationCode;
import com.fonja.notification.event.NotificationEvent.PasswordResetLink;
import com.fonja.notification.event.NotificationEvent.VerificationReviewed;
import com.fonja.notification.event.NotificationEvent.WithdrawalRequested;
import com.fonja.notification.event.NotificationEvent.WithdrawalSettled;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The in-app counterpart of {@link EmailComposer}: a short title, one sentence, and where clicking
 * it should take you. Links are app paths, not URLs, so the frontend routes them.
 *
 * <p>Auth messages — sign-up codes and reset links — are email-only: they are secrets, and the
 * person reading them is by definition not signed in yet.
 */
@Component
public class InAppComposer {

    public record InApp(String title, String body, String link) {}

    public Optional<InApp> compose(NotificationEvent event) {
        if (event instanceof EmailVerificationCode || event instanceof PasswordResetLink) {
            return Optional.empty();
        }
        return Optional.of(message(event));
    }

    private InApp message(NotificationEvent event) {
        if (event instanceof DonationReceipt e) {
            return new InApp(
                    "Thank you for your donation",
                    "Your "
                            + EmailComposer.money(e.amount(), e.currency())
                            + " to \""
                            + e.campaignTitle()
                            + "\" went through.",
                    "/campaigns/" + e.campaignId());
        }
        if (event instanceof DonationReceived e) {
            String donor =
                    e.donorName() == null || e.donorName().isBlank() ? "Someone" : e.donorName();
            return new InApp(
                    "New donation",
                    donor
                            + " gave "
                            + EmailComposer.money(e.amount(), e.currency())
                            + " to \""
                            + e.campaignTitle()
                            + "\".",
                    "/dashboard");
        }
        if (event instanceof CampaignReviewed e) {
            return e.approved()
                    ? new InApp(
                            "Your campaign is live",
                            "\"" + e.campaignTitle() + "\" was approved and can take donations.",
                            "/dashboard/campaigns?celebrate=" + e.campaignId())
                    : new InApp(
                            "Your campaign needs changes",
                            "\""
                                    + e.campaignTitle()
                                    + "\" wasn't approved"
                                    + reasonSuffix(e.reason()),
                            "/dashboard/campaigns/" + e.campaignId() + "/edit");
        }
        if (event instanceof CampaignCompleted e) {
            return new InApp(
                    "You reached your goal",
                    "\""
                            + e.campaignTitle()
                            + "\" raised "
                            + EmailComposer.money(e.raisedAmount(), e.currency())
                            + ". Congratulations!",
                    "/dashboard/payouts");
        }
        if (event instanceof VerificationReviewed e) {
            return e.approved()
                    ? new InApp(
                            "You're a verified creator",
                            "Your campaigns can now go live.",
                            "/dashboard/campaigns/new")
                    : new InApp(
                            "Verification not approved",
                            "We couldn't approve your verification" + reasonSuffix(e.reason()),
                            "/dashboard/verification");
        }
        if (event instanceof WithdrawalRequested e) {
            return new InApp(
                    "Withdrawal requested",
                    EmailComposer.money(e.amount(), e.currency()) + " is on its way to your bank.",
                    "/dashboard/payouts");
        }
        if (event instanceof WithdrawalSettled e) {
            String amount = EmailComposer.money(e.amount(), e.currency());
            return e.paid()
                    ? new InApp(
                            "Money sent",
                            amount + " has been sent to your bank account.",
                            "/dashboard/payouts")
                    : new InApp(
                            "Withdrawal failed",
                            "Your "
                                    + amount
                                    + " withdrawal didn't go through and is back in your"
                                    + " earnings.",
                            "/dashboard/payouts");
        }
        if (event instanceof CampaignCommentPosted e) {
            String who =
                    e.commenterName() == null || e.commenterName().isBlank()
                            ? "Someone"
                            : e.commenterName();
            return new InApp(
                    who + " commented on \"" + e.campaignTitle() + "\"",
                    e.excerpt(),
                    "/campaigns/" + e.campaignId() + "?tab=comments");
        }
        throw new IllegalArgumentException("No in-app message for " + event.getClass().getName());
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "." : ": " + reason.trim();
    }
}
