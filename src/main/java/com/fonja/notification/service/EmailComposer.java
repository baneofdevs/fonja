package com.fonja.notification.service;

import com.fonja.notification.config.NotificationConfig;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Turns an event into an email: a subject, a plain-text body and a branded HTML body.
 *
 * <p>Each message is written once, as a {@link Message} — heading, paragraphs, an optional code,
 * key facts, one button — and rendered twice. The HTML goes through {@code
 * templates/email/layout.html}, the one place the look lives; the text is the multipart fallback
 * and what tests read. Keeping the copy here, beside the facts it states, means an amount or a
 * reason cannot be right in one version and missing from the other.
 */
@Component
public class EmailComposer {

    private final NotificationConfig config;
    private final TemplateEngine templates;

    public EmailComposer(NotificationConfig config) {
        this.config = config;
        this.templates = emailTemplateEngine();
    }

    /** {@code body} is the plain-text part; {@code html} the branded one. */
    public record Email(String subject, String body, String html) {}

    /** One row in the key-facts table, and one {@code Label: value} line in the text version. */
    public record Detail(String label, String value) {}

    public record Cta(String label, String url) {}

    // instanceof chain rather than a pattern switch: the project compiles for Java 17, where
    // switching on a sealed type is still a preview feature. The sealed interface still keeps the
    // list closed; a new event type fails here at runtime rather than silently going unsent.
    public Email compose(NotificationEvent event) {
        return render(event, message(event));
    }

    private Message message(NotificationEvent event) {
        if (event instanceof DonationReceipt e) {
            return donationReceipt(e);
        }
        if (event instanceof DonationReceived e) {
            return donationReceived(e);
        }
        if (event instanceof CampaignReviewed e) {
            return e.approved() ? campaignApproved(e) : campaignRejected(e);
        }
        if (event instanceof CampaignCompleted e) {
            return campaignCompleted(e);
        }
        if (event instanceof VerificationReviewed e) {
            return e.approved() ? verificationApproved(e) : verificationRejected(e);
        }
        if (event instanceof WithdrawalRequested e) {
            return withdrawalRequested(e);
        }
        if (event instanceof WithdrawalSettled e) {
            return e.paid() ? withdrawalPaid(e) : withdrawalFailed(e);
        }
        if (event instanceof EmailVerificationCode e) {
            return emailVerificationCode(e);
        }
        if (event instanceof PasswordResetLink e) {
            return passwordReset(e);
        }
        if (event instanceof CampaignCommentPosted e) {
            return commentPosted(e);
        }
        throw new IllegalArgumentException("No email template for " + event.getClass().getName());
    }

    // --- messages -------------------------------------------------------------------

    private Message donationReceipt(DonationReceipt e) {
        String amount = money(e.amount(), e.currency());
        return new Message(
                        "Thank you for giving " + amount + " to " + e.campaignTitle(),
                        "Thank you for your donation")
                .preheader("Your " + amount + " donation has gone through.")
                .paragraph(
                        "Your donation of "
                                + amount
                                + " to \""
                                + e.campaignTitle()
                                + "\" has gone through. Thank you.")
                .paragraph(
                        "The creator has been told, and the money is already counted towards"
                                + " their goal.")
                .detail("Amount", amount)
                .detail("Campaign", e.campaignTitle())
                .cta("View the campaign", campaignUrl(e.campaignId()))
                .footnote("Keep this email as your receipt.");
    }

    private Message donationReceived(DonationReceived e) {
        String amount = money(e.amount(), e.currency());
        String donor = e.donorName() == null || e.donorName().isBlank() ? "Someone" : e.donorName();
        return new Message(donor + " gave " + amount + " to " + e.campaignTitle(), "New donation")
                .preheader(donor + " just supported your campaign.")
                .paragraph(donor + " just donated " + amount + " to \"" + e.campaignTitle() + "\".")
                .paragraph(
                        "Fonja's platform fee has been taken and the rest is in your earnings —"
                                + " you can see the exact figure on your dashboard.")
                .detail("From", donor)
                .detail("Amount", amount)
                .cta("Open your dashboard", dashboardUrl());
    }

    private Message campaignApproved(CampaignReviewed e) {
        return new Message("\"" + e.campaignTitle() + "\" is live", "Your campaign is live")
                .preheader("It can take donations from now.")
                .paragraph(
                        "Your campaign \""
                                + e.campaignTitle()
                                + "\" has been reviewed and approved. It is now live on Fonja"
                                + " and can take donations.")
                .paragraph(
                        "Share the link — campaigns that get their first few donations early do"
                                + " best.")
                .cta("View your campaign", campaignUrl(e.campaignId()));
    }

    private Message campaignRejected(CampaignReviewed e) {
        return new Message(
                        "\"" + e.campaignTitle() + "\" needs changes before it can go live",
                        "Your campaign needs changes")
                .preheader("A reviewer left a note on what to fix.")
                .paragraph(
                        "Your campaign \""
                                + e.campaignTitle()
                                + "\" was reviewed and could not be approved yet.")
                .detail("Reason", orNone(e.reason()))
                .paragraph(
                        "It has gone back to draft. Make the changes and launch it again for"
                                + " another review.")
                .cta("Edit your campaign", campaignUrl(e.campaignId()));
    }

    private Message campaignCompleted(CampaignCompleted e) {
        return new Message(
                        "\"" + e.campaignTitle() + "\" reached its goal", "You reached your goal")
                .preheader("Congratulations — your campaign is fully funded.")
                .paragraph(
                        "\""
                                + e.campaignTitle()
                                + "\" has raised "
                                + money(e.raisedAmount(), e.currency())
                                + " against a goal of "
                                + money(e.goalAmount(), e.currency())
                                + ". Congratulations.")
                .paragraph(
                        "The campaign is now closed to new donations. Everything raised, less"
                                + " Fonja's platform fee, is in your earnings and can be"
                                + " withdrawn to your bank account.")
                .detail("Raised", money(e.raisedAmount(), e.currency()))
                .detail("Goal", money(e.goalAmount(), e.currency()))
                .cta("Withdraw your funds", dashboardUrl());
    }

    private Message verificationApproved(VerificationReviewed e) {
        return new Message("You are now a verified creator on Fonja", "You're verified")
                .preheader("Your campaigns can now go live.")
                .paragraph(
                        "Your identity verification has been approved. Campaigns you launch can"
                                + " now be reviewed and go live, and you can withdraw what they"
                                + " raise.")
                .cta("Start a campaign", url("/dashboard/campaigns/new"));
    }

    private Message verificationRejected(VerificationReviewed e) {
        return new Message(
                        "Your Fonja verification could not be approved",
                        "We couldn't verify you yet")
                .preheader("Here's why, and what to do next.")
                .paragraph("We reviewed your identity verification and could not approve it.")
                .detail("Reason", orNone(e.reason()))
                .paragraph(
                        "You can submit a new verification request with corrected documents at"
                                + " any time.")
                .cta("Try again", url("/dashboard/verification"));
    }

    private Message withdrawalRequested(WithdrawalRequested e) {
        String amount = money(e.amount(), e.currency());
        return new Message("Withdrawal of " + amount + " requested", "Withdrawal requested")
                .preheader("We're sending " + amount + " to your bank.")
                .paragraph(
                        "We have received your request to withdraw "
                                + amount
                                + " to "
                                + bank(e.bankName(), e.maskedAccountNumber())
                                + ".")
                .paragraph(
                        "That amount plus the "
                                + money(e.fee(), e.currency())
                                + " withdrawal fee has been set aside from your earnings. We will"
                                + " email you again when the transfer lands, or if it cannot be"
                                + " completed.")
                .detail("Amount", amount)
                .detail("Fee", money(e.fee(), e.currency()))
                .detail("To", bank(e.bankName(), e.maskedAccountNumber()))
                .detail("Reference", "#" + e.payoutId())
                .cta("View your payouts", url("/dashboard/payouts"));
    }

    private Message withdrawalPaid(WithdrawalSettled e) {
        String amount = money(e.amount(), e.currency());
        return new Message(amount + " has been sent to your bank account", "Money sent")
                .preheader(amount + " is on its way to your bank.")
                .paragraph(
                        amount
                                + " has been transferred to "
                                + bank(e.bankName(), e.maskedAccountNumber())
                                + ". Depending on the bank it can take a few minutes to show.")
                .detail("Amount", amount)
                .detail("To", bank(e.bankName(), e.maskedAccountNumber()))
                .detail("Reference", "#" + e.payoutId())
                .cta("View your payouts", url("/dashboard/payouts"));
    }

    private Message withdrawalFailed(WithdrawalSettled e) {
        String amount = money(e.amount(), e.currency());
        return new Message(
                        "Your withdrawal of " + amount + " could not be completed",
                        "Your withdrawal didn't go through")
                .preheader("The money is back in your earnings.")
                .paragraph(
                        "The transfer of "
                                + amount
                                + " to "
                                + bank(e.bankName(), e.maskedAccountNumber())
                                + " did not go through.")
                .detail("Reason", orNone(e.failureReason()))
                .detail("Reference", "#" + e.payoutId())
                .paragraph(
                        "The full "
                                + money(e.amount().add(e.fee()), e.currency())
                                + " — the amount and the fee — is already back in your earnings."
                                + " Check your bank details and try again, or contact us if the"
                                + " reason is unclear.")
                .cta("Check your bank details", url("/dashboard/payouts"));
    }

    private Message emailVerificationCode(EmailVerificationCode e) {
        return new Message(e.code() + " is your Fonja verification code", "Verify your email")
                .preheader("Your code is " + e.code() + ".")
                .paragraph("Enter this code on Fonja to verify your email address:")
                .code(e.code())
                .footnote("The code expires in " + e.validForMinutes() + " minutes.")
                .footnote(
                        "If you didn't create a Fonja account, you can ignore this email —"
                                + " nothing happens without the code.");
    }

    private Message passwordReset(PasswordResetLink e) {
        return new Message("Reset your Fonja password", "Reset your password")
                .preheader("Choose a new password for your Fonja account.")
                .paragraph(
                        "We received a request to reset the password on your Fonja account. Use"
                                + " the button below to choose a new one.")
                .cta("Reset password", e.resetUrl())
                .footnote("The link works once and expires in " + e.validForMinutes() + " minutes.")
                .footnote(
                        "If you didn't ask for this, ignore this email — your password stays the"
                                + " same.");
    }

    private Message commentPosted(CampaignCommentPosted e) {
        String who =
                e.commenterName() == null || e.commenterName().isBlank()
                        ? "Someone"
                        : e.commenterName();
        return new Message(who + " commented on " + e.campaignTitle(), "New comment")
                .preheader("\"" + e.excerpt() + "\"")
                .paragraph(who + " left a comment on \"" + e.campaignTitle() + "\":")
                .paragraph("\"" + e.excerpt() + "\"")
                .cta("Reply", url("/campaigns/" + e.campaignId() + "?tab=comments"));
    }

    // --- rendering ------------------------------------------------------------------

    private Email render(NotificationEvent event, Message m) {
        String greeting = greeting(event);
        return new Email(m.subject, text(greeting, m), html(greeting, m));
    }

    private static String text(String greeting, Message m) {
        StringBuilder out = new StringBuilder(greeting).append("\n\n");
        for (String paragraph : m.paragraphs) {
            out.append(paragraph).append("\n\n");
        }
        if (m.code != null) {
            out.append("    ").append(m.code).append("\n\n");
        }
        if (!m.details.isEmpty()) {
            for (Detail d : m.details) {
                out.append(d.label()).append(": ").append(d.value()).append('\n');
            }
            out.append('\n');
        }
        if (m.cta != null) {
            out.append(m.cta.label()).append(":\n").append(m.cta.url()).append("\n\n");
        }
        for (String note : m.footnotes) {
            out.append(note).append("\n\n");
        }
        return out.append("— The Fonja team\n").toString();
    }

    private String html(String greeting, Message m) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("subject", m.subject);
        vars.put("preheader", m.preheader == null ? "" : m.preheader);
        vars.put("heading", m.heading);
        vars.put("greeting", greeting);
        vars.put("paragraphs", m.paragraphs);
        vars.put("code", m.code);
        // Maps rather than the records: the template's expression language (OGNL, without Spring)
        // reads map keys and bean getters, not record accessors.
        vars.put(
                "details",
                m.details.stream()
                        .map(d -> Map.of("label", d.label(), "value", d.value()))
                        .toList());
        vars.put("cta", m.cta == null ? null : Map.of("label", m.cta.label(), "url", m.cta.url()));
        vars.put("footnotes", m.footnotes);
        // Thymeleaf treats "" as true, so a blank base URL goes in as null.
        vars.put("appBaseUrl", baseUrl().isEmpty() ? null : baseUrl());
        vars.put("year", Year.now().getValue());
        return templates.process("layout", new Context(Locale.UK, vars));
    }

    private static TemplateEngine emailTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    // --- fragments ------------------------------------------------------------------

    private static String greeting(NotificationEvent e) {
        String name = e.recipient().name();
        return "Hi" + (name == null || name.isBlank() ? "" : " " + firstName(name)) + ",";
    }

    private static String firstName(String fullName) {
        return fullName.trim().split("\\s+")[0];
    }

    private static String orNone(String reason) {
        return reason == null || reason.isBlank() ? "not given" : reason.trim();
    }

    private static String bank(String bankName, String maskedAccountNumber) {
        if (bankName == null || bankName.isBlank()) {
            return "your bank account";
        }
        return bankName + (maskedAccountNumber == null ? "" : " " + maskedAccountNumber);
    }

    private String baseUrl() {
        String base = config.getAppBaseUrl() == null ? "" : config.getAppBaseUrl().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** A link into the app, or null when no app URL is configured — the button is then left out. */
    private String url(String path) {
        return baseUrl().isEmpty() ? null : baseUrl() + path;
    }

    private String campaignUrl(Long campaignId) {
        return url("/campaigns/" + campaignId);
    }

    private String dashboardUrl() {
        return url("/dashboard");
    }

    /**
     * {@code ₦5,000.00} for NGN; any other currency falls back to its code, e.g. {@code USD
     * 5,000.00}.
     */
    static String money(BigDecimal amount, String currency) {
        DecimalFormat format =
                new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK));
        String number = format.format(amount.setScale(2, RoundingMode.HALF_UP));
        return "NGN".equalsIgnoreCase(currency) ? "₦" + number : currency + " " + number;
    }

    /** A message before rendering. Built fluently by the methods above; order is display order. */
    private static final class Message {
        private final String subject;
        private final String heading;
        private String preheader;
        private final List<String> paragraphs = new ArrayList<>();
        private String code;
        private final List<Detail> details = new ArrayList<>();
        private Cta cta;
        private final List<String> footnotes = new ArrayList<>();

        Message(String subject, String heading) {
            this.subject = subject;
            this.heading = heading;
        }

        Message preheader(String text) {
            this.preheader = text;
            return this;
        }

        Message paragraph(String text) {
            paragraphs.add(text);
            return this;
        }

        Message code(String value) {
            this.code = value;
            return this;
        }

        Message detail(String label, String value) {
            details.add(new Detail(label, value));
            return this;
        }

        /** A null URL (no app URL configured) drops the button rather than linking nowhere. */
        Message cta(String label, String url) {
            this.cta = url == null ? null : new Cta(label, url);
            return this;
        }

        Message footnote(String text) {
            footnotes.add(text);
            return this;
        }
    }
}
