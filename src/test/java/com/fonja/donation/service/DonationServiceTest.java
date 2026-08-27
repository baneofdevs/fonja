package com.fonja.donation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.donation.dto.CyberPayTransaction;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.entity.PaymentMethod;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.ledger.service.LedgerService;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * These are mostly tests about what <em>not</em> to do. Crediting a creator for money that never
 * arrived is the worst bug this module can have, so most cases here assert that the ledger was left
 * alone.
 */
@ExtendWith(MockitoExtension.class)
class DonationServiceTest {

    private static final String REFERENCE = "KIN190523841971254995";
    private static final Long DONATION_ID = 10L;
    private static final BigDecimal AMOUNT = new BigDecimal("5000.00");

    @Mock private DonationRepository donationRepository;
    @Mock private CyberPayClient cyberPayClient;
    @Mock private LedgerService ledgerService;

    private DonationService donationService;

    @BeforeEach
    void setUp() {
        donationService = new DonationService(donationRepository, cyberPayClient, ledgerService);
    }

    @Test
    void confirmsAndPostsToTheLedgerWhenCyberPayAgrees() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Successful", AMOUNT);

        donationService.confirmFromWebhook(REFERENCE, "BankTransfer");

        assertThat(donation.getStatus()).isEqualTo(DonationStatus.SUCCESS);
        assertThat(donation.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        verify(ledgerService).recordDonation(donation);
    }

    @Test
    void neverCreditsOnTheWebhookAlone() {
        // The signature proves the message is CyberPay's; only the re-query proves the payment. If
        // this ever passes without a re-query, the doorbell has become the bank statement.
        givenDonation(pending());
        givenCyberPaySays("Successful", AMOUNT);

        donationService.confirmFromWebhook(REFERENCE, "Card");

        verify(cyberPayClient).requery(REFERENCE);
    }

    @Test
    void marksFailedWithoutTouchingTheLedgerWhenCyberPaySaysAbandoned() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Abandoned", BigDecimal.ZERO);

        donationService.confirmFromWebhook(REFERENCE, null);

        assertThat(donation.getStatus()).isEqualTo(DonationStatus.FAILED);
        verify(ledgerService, never()).recordDonation(any());
    }

    @Test
    void asksAgainLaterWhenCyberPayHasNotReachedAVerdict() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Pending", AMOUNT);

        // 503 makes CyberPay redeliver in an hour, which is exactly what an in-flight payment
        // needs.
        assertGatewayUnavailable(() -> donationService.confirmFromWebhook(REFERENCE, null));

        assertThat(donation.getStatus()).isEqualTo(DonationStatus.PENDING);
        verify(ledgerService, never()).recordDonation(any());
    }

    @Test
    void treatsAnUnrecognisedStatusAsNotYetDecided() {
        // A status CyberPay adds after this was written must not credit anyone, and must not
        // permanently fail a donation that might still complete.
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("AwaitingSettlement", AMOUNT);

        assertGatewayUnavailable(() -> donationService.confirmFromWebhook(REFERENCE, null));

        assertThat(donation.getStatus()).isEqualTo(DonationStatus.PENDING);
        verify(ledgerService, never()).recordDonation(any());
    }

    @Test
    void refusesToCreditWhenTheAmountDisagrees() {
        // The likeliest cause is the kobo-versus-naira assumption in CyberPayClient being wrong —
        // in which case this is the difference between a ₦50 donation and a ₦5,000 credit.
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Successful", new BigDecimal("50.00"));

        donationService.confirmFromWebhook(REFERENCE, "Card");

        // Left PENDING, not FAILED: nobody has established what happened yet.
        assertThat(donation.getStatus()).isEqualTo(DonationStatus.PENDING);
        verify(ledgerService, never()).recordDonation(any());
    }

    @Test
    void acceptsAmountsThatDifferOnlyInScale() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Successful", new BigDecimal("5000.0"));

        donationService.confirmFromWebhook(REFERENCE, "Card");

        assertThat(donation.getStatus()).isEqualTo(DonationStatus.SUCCESS);
        verify(ledgerService).recordDonation(donation);
    }

    @Test
    void acceptsAndIgnoresANotificationForAReferenceWeDoNotHave() {
        when(donationRepository.findByGatewayReference(REFERENCE)).thenReturn(Optional.empty());

        // Returns normally — a 200 — because 72 hours of retries will not make the donation exist.
        donationService.confirmFromWebhook(REFERENCE, "Card");

        verifyNoInteractions(cyberPayClient, ledgerService);
    }

    @Test
    void reAssertsTheLedgerOnADuplicateDeliveryWithoutReQuerying() {
        // CyberPay redelivers hourly for 72 hours. Re-posting is free — LedgerService is idempotent
        // — and it repairs the case where a previous attempt marked the donation then died before
        // writing the entries.
        Donation donation = pending();
        ReflectionTestUtils.setField(donation, "status", DonationStatus.SUCCESS);
        givenDonation(donation);

        donationService.confirmFromWebhook(REFERENCE, "Card");

        verify(ledgerService).recordDonation(donation);
        verifyNoInteractions(cyberPayClient);
    }

    @Test
    void ignoresANotificationForADonationAlreadyMarkedFailed() {
        Donation donation = pending();
        ReflectionTestUtils.setField(donation, "status", DonationStatus.FAILED);
        givenDonation(donation);

        donationService.confirmFromWebhook(REFERENCE, "Card");

        verifyNoInteractions(cyberPayClient, ledgerService);
    }

    @Test
    void propagatesAGatewayFailureSoCyberPayRetries() {
        givenDonation(pending());
        when(cyberPayClient.requery(REFERENCE))
                .thenThrow(ApiException.gatewayUnavailable("unreachable"));

        assertGatewayUnavailable(() -> donationService.confirmFromWebhook(REFERENCE, null));

        verify(ledgerService, never()).recordDonation(any());
    }

    // --- helpers ---------------------------------------------------------------------

    private void givenDonation(Donation donation) {
        when(donationRepository.findByGatewayReference(REFERENCE))
                .thenReturn(Optional.of(donation));
    }

    private void givenCyberPaySays(String status, BigDecimal amount) {
        when(cyberPayClient.requery(anyString()))
                .thenReturn(new CyberPayTransaction(REFERENCE, status, amount));
    }

    private void assertGatewayUnavailable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.GATEWAY_UNAVAILABLE);
    }

    private Donation pending() {
        User creator = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub-1");
        ReflectionTestUtils.setField(creator, "id", 1L);
        Campaign campaign =
                new Campaign(
                        creator,
                        "Title",
                        "Story",
                        CampaignCategory.MEDICAL,
                        BeneficiaryType.SELF,
                        "Nigeria",
                        null,
                        BigDecimal.valueOf(500_000),
                        "NGN",
                        false,
                        "cover.jpg",
                        null);
        Donation donation = new Donation(campaign, creator, AMOUNT, "NGN", REFERENCE);
        ReflectionTestUtils.setField(donation, "id", DONATION_ID);
        return donation;
    }
}
