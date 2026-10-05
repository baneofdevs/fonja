package com.fonja.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fonja.notification.config.NotificationConfig;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The copy is prose, but the facts in it — amounts, names, reasons, links — are load-bearing. */
class EmailComposerTest {

    private static final Recipient ADA = new Recipient(1L, "ada@example.com", "Ada Obi");

    private EmailComposer composer(String baseUrl) {
        NotificationConfig config = new NotificationConfig();
        config.setAppBaseUrl(baseUrl);
        return new EmailComposer(config);
    }

    @Test
    void formatsNairaWithGroupingAndTwoDecimals() {
        assertThat(EmailComposer.money(new BigDecimal("5000"), "NGN")).isEqualTo("₦5,000.00");
        assertThat(EmailComposer.money(new BigDecimal("1234567.891"), "NGN"))
                .isEqualTo("₦1,234,567.89");
        assertThat(EmailComposer.money(new BigDecimal("20"), "USD")).isEqualTo("USD 20.00");
    }

    @Test
    void receiptNamesTheAmountTheCampaignAndTheLink() {
        EmailComposer.Email email =
                composer("https://fonja.test/")
                        .compose(
                                new NotificationEvent.DonationReceipt(
                                        ADA,
                                        42L,
                                        "Surgery for Ada",
                                        new BigDecimal("5000"),
                                        "NGN"));

        assertThat(email.subject()).isEqualTo("Thank you for giving ₦5,000.00 to Surgery for Ada");
        assertThat(email.body())
                .startsWith("Hi Ada,")
                .contains("₦5,000.00")
                .contains("\"Surgery for Ada\"")
                .contains("https://fonja.test/campaigns/42");
    }

    @Test
    void linksAreOmittedWhenNoAppUrlIsConfigured() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.CampaignReviewed(
                                        ADA, 42L, "Surgery for Ada", true, null));

        assertThat(email.body()).doesNotContain("http");
    }

    @Test
    void rejectionCarriesTheReasonAndSaysWhatToDoNext() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.CampaignReviewed(
                                        ADA, 42L, "Surgery for Ada", false, "Story is too thin"));

        assertThat(email.subject()).contains("needs changes");
        assertThat(email.body()).contains("Reason: Story is too thin").contains("launch it again");
    }

    @Test
    void aMissingReasonReadsAsNotGivenRatherThanNull() {
        EmailComposer.Email email =
                composer("").compose(new NotificationEvent.VerificationReviewed(ADA, false, null));

        assertThat(email.body()).contains("Reason: not given").doesNotContain("null");
    }

    @Test
    void failedWithdrawalStatesTheFullRefundIncludingTheFee() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.WithdrawalSettled(
                                        ADA,
                                        20L,
                                        new BigDecimal("5000"),
                                        new BigDecimal("200"),
                                        "NGN",
                                        "GTBank",
                                        "******6789",
                                        false,
                                        "Beneficiary account closed"));

        assertThat(email.subject())
                .isEqualTo("Your withdrawal of ₦5,000.00 could not be completed");
        assertThat(email.body())
                .contains("GTBank ******6789")
                .contains("Reason: Beneficiary account closed")
                .contains("The full ₦5,200.00")
                .contains("Reference: #20");
    }

    @Test
    void aDeletedBankAccountFallsBackToAGenericPhrase() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.WithdrawalSettled(
                                        ADA,
                                        20L,
                                        new BigDecimal("5000"),
                                        new BigDecimal("200"),
                                        "NGN",
                                        null,
                                        null,
                                        true,
                                        null));

        assertThat(email.body()).contains("transferred to your bank account");
    }

    @Test
    void anAnonymousDonorNameStillReadsNaturally() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.DonationReceived(
                                        ADA,
                                        42L,
                                        "Surgery for Ada",
                                        "",
                                        new BigDecimal("5000"),
                                        "NGN"));

        assertThat(email.subject()).startsWith("Someone gave ₦5,000.00");
    }

    // --- HTML ------------------------------------------------------------------------

    @Test
    void htmlCarriesTheSameFactsAsTheTextAndTheButton() {
        EmailComposer.Email email =
                composer("https://fonja.test/")
                        .compose(
                                new NotificationEvent.DonationReceipt(
                                        ADA,
                                        42L,
                                        "Surgery for Ada",
                                        new BigDecimal("5000"),
                                        "NGN"));

        assertThat(email.html())
                .contains("<!DOCTYPE html>")
                .contains("Thank you for your donation")
                .contains("Hi Ada,")
                .contains("₦5,000.00")
                .contains("href=\"https://fonja.test/campaigns/42\"")
                .contains("https://fonja.test/logo.png");
    }

    @Test
    void userTextIsEscapedInTheHtml() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.CampaignReviewed(
                                        ADA, 42L, "<script>alert(1)</script>", true, null));

        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void withoutAnAppUrlThereIsNoButtonAndNoLogoLink() {
        EmailComposer.Email email =
                composer("")
                        .compose(
                                new NotificationEvent.CampaignReviewed(
                                        ADA, 42L, "Surgery for Ada", true, null));

        assertThat(email.html()).doesNotContain("href=").doesNotContain("logo.png");
    }

    @Test
    void theVerificationCodeIsInTheSubjectTheTextAndTheHtml() {
        EmailComposer.Email email =
                composer("https://fonja.test")
                        .compose(new NotificationEvent.EmailVerificationCode(ADA, "482913", 15));

        assertThat(email.subject()).startsWith("482913");
        assertThat(email.body()).contains("482913").contains("15 minutes");
        assertThat(email.html()).contains("482913");
    }

    @Test
    void theResetEmailLinksToTheTokenUrl() {
        String url = "https://fonja.test/reset-password?token=abc";
        EmailComposer.Email email =
                composer("https://fonja.test")
                        .compose(new NotificationEvent.PasswordResetLink(ADA, url, 30));

        assertThat(email.body()).contains(url);
        assertThat(email.html()).contains("href=\"" + url + "\"");
    }

    /**
     * Renders one of every email and writes it to {@code target/email-previews}, so the layout can
     * be checked in a browser after {@code ./mvnw test} without sending anything.
     */
    @Test
    void everyEventRendersAndIsWrittenOutForPreview() throws IOException {
        EmailComposer composer = composer("http://localhost:5173");
        BigDecimal amount = new BigDecimal("50000");
        BigDecimal fee = new BigDecimal("100");
        List<NotificationEvent> events =
                List.of(
                        new NotificationEvent.DonationReceipt(
                                ADA, 42L, "Help Ada finish her final year", amount, "NGN"),
                        new NotificationEvent.DonationReceived(
                                ADA, 42L, "Help Ada finish her final year", "Tunde", amount, "NGN"),
                        new NotificationEvent.CampaignReviewed(
                                ADA, 42L, "Help Ada finish her final year", true, null),
                        new NotificationEvent.CampaignReviewed(
                                ADA, 42L, "Help Ada finish her final year", false, "Add a photo"),
                        new NotificationEvent.CampaignCompleted(
                                ADA, 42L, "Help Ada finish her final year", amount, amount, "NGN"),
                        new NotificationEvent.VerificationReviewed(ADA, true, null),
                        new NotificationEvent.VerificationReviewed(ADA, false, "ID is blurry"),
                        new NotificationEvent.WithdrawalRequested(
                                ADA, 20L, amount, fee, "NGN", "GTBank", "******6789"),
                        new NotificationEvent.WithdrawalSettled(
                                ADA, 20L, amount, fee, "NGN", "GTBank", "******6789", true, null),
                        new NotificationEvent.WithdrawalSettled(
                                ADA,
                                20L,
                                amount,
                                fee,
                                "NGN",
                                "GTBank",
                                "******6789",
                                false,
                                "Account closed"),
                        new NotificationEvent.EmailVerificationCode(ADA, "482913", 15),
                        new NotificationEvent.PasswordResetLink(
                                ADA, "http://localhost:5173/reset-password?token=abc", 30),
                        new NotificationEvent.CampaignCommentPosted(
                                ADA,
                                42L,
                                "Help Ada finish her final year",
                                9L,
                                "Tunde Bello",
                                "Rooting for you, Ada!"));

        Path dir = Path.of("target", "email-previews");
        Files.createDirectories(dir);
        for (NotificationEvent event : events) {
            EmailComposer.Email email = composer.compose(event);
            assertThat(email.subject()).isNotBlank();
            assertThat(email.body()).startsWith("Hi Ada,").endsWith("— The Fonja team\n");
            assertThat(email.html()).contains("— The Fonja team").doesNotContain("null");
            Files.writeString(
                    dir.resolve(event.type().name().toLowerCase() + ".html"), email.html());
        }
    }
}
