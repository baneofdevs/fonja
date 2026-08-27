package com.fonja.donation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.donation.dto.CyberPayCheckout;
import com.fonja.donation.dto.CyberPayTransaction;
import com.fonja.donation.dto.InitiateDonationRequest;
import com.fonja.donation.dto.InitiateDonationResponse;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.entity.PaymentMethod;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.ledger.service.LedgerService;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * These are mostly tests about what <em>not</em> to do. Crediting a creator for money that never
 * arrived is the worst bug this module can have, so most cases here assert that the ledger was left
 * alone.
 */
@ExtendWith(MockitoExtension.class)
class DonationServiceTest {

    private static final String REFERENCE = "KIN190523841971254995";
    private static final String MERCHANT_REF = "FNJ0123456789abcdef";
    private static final Long DONATION_ID = 10L;
    private static final Long CAMPAIGN_ID = 7L;
    private static final Long DONOR_ID = 1L;
    private static final BigDecimal AMOUNT = new BigDecimal("5000.00");

    @Mock private DonationRepository donationRepository;
    @Mock private CampaignService campaignService;
    @Mock private UserService userService;
    @Mock private CyberPayClient cyberPayClient;
    @Mock private LedgerService ledgerService;

    private DonationService donationService;

    @BeforeEach
    void setUp() {
        donationService =
                new DonationService(
                        donationRepository,
                        campaignService,
                        userService,
                        cyberPayClient,
                        ledgerService,
                        immediateTransactions());
    }

    /**
     * A TransactionTemplate that just runs the callback. The service uses three separate
     * transactions on the initiate path deliberately, and these tests are about what it does in
     * each — not about the transaction manager, which has no business being in a unit test.
     */
    private static TransactionTemplate immediateTransactions() {
        PlatformTransactionManager noop =
                new AbstractPlatformTransactionManager() {
                    @Override
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    @Override
                    protected void doBegin(Object transaction, TransactionDefinition definition) {}

                    @Override
                    protected void doCommit(DefaultTransactionStatus status) {}

                    @Override
                    protected void doRollback(DefaultTransactionStatus status) {}
                };
        return new TransactionTemplate(noop);
    }

    @Test
    void confirmsAndPostsToTheLedgerWhenCyberPayAgrees() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Successful", AMOUNT);

        donationService.confirmFromWebhook(REFERENCE, null, "BankTransfer");

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

        donationService.confirmFromWebhook(REFERENCE, null, "Card");

        verify(cyberPayClient).requery(REFERENCE);
    }

    @Test
    void marksFailedWithoutTouchingTheLedgerWhenCyberPaySaysAbandoned() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Abandoned", BigDecimal.ZERO);

        donationService.confirmFromWebhook(REFERENCE, null, null);

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
        assertGatewayUnavailable(() -> donationService.confirmFromWebhook(REFERENCE, null, null));

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

        assertGatewayUnavailable(() -> donationService.confirmFromWebhook(REFERENCE, null, null));

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

        donationService.confirmFromWebhook(REFERENCE, null, "Card");

        // Left PENDING, not FAILED: nobody has established what happened yet.
        assertThat(donation.getStatus()).isEqualTo(DonationStatus.PENDING);
        verify(ledgerService, never()).recordDonation(any());
    }

    @Test
    void acceptsAmountsThatDifferOnlyInScale() {
        Donation donation = pending();
        givenDonation(donation);
        givenCyberPaySays("Successful", new BigDecimal("5000.0"));

        donationService.confirmFromWebhook(REFERENCE, null, "Card");

        assertThat(donation.getStatus()).isEqualTo(DonationStatus.SUCCESS);
        verify(ledgerService).recordDonation(donation);
    }

    @Test
    void acceptsAndIgnoresANotificationForAReferenceWeDoNotHave() {
        when(donationRepository.findByGatewayReference(REFERENCE)).thenReturn(Optional.empty());

        // Returns normally — a 200 — because 72 hours of retries will not make the donation exist.
        donationService.confirmFromWebhook(REFERENCE, null, "Card");

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

        donationService.confirmFromWebhook(REFERENCE, null, "Card");

        verify(ledgerService).recordDonation(donation);
        verifyNoInteractions(cyberPayClient);
    }

    @Test
    void ignoresANotificationForADonationAlreadyMarkedFailed() {
        Donation donation = pending();
        ReflectionTestUtils.setField(donation, "status", DonationStatus.FAILED);
        givenDonation(donation);

        donationService.confirmFromWebhook(REFERENCE, null, "Card");

        verifyNoInteractions(cyberPayClient, ledgerService);
    }

    @Test
    void propagatesAGatewayFailureSoCyberPayRetries() {
        givenDonation(pending());
        when(cyberPayClient.requery(REFERENCE))
                .thenThrow(ApiException.gatewayUnavailable("unreachable"));

        assertGatewayUnavailable(() -> donationService.confirmFromWebhook(REFERENCE, null, null));

        verify(ledgerService, never()).recordDonation(any());
    }

    // --- initiate --------------------------------------------------------------------

    @Test
    void opensCheckoutAndStoresBothReferences() {
        List<Donation> saved = givenCheckoutSucceeds();

        InitiateDonationResponse response =
                donationService.initiate(
                        DONOR_ID, new InitiateDonationRequest(CAMPAIGN_ID, AMOUNT));

        Donation donation = saved.get(0);
        assertThat(response.checkoutUrl()).isEqualTo("https://payment.cyberpay.ng/pay?ref=x");
        assertThat(response.merchantReference()).isEqualTo(donation.getMerchantReference());
        assertThat(donation.getGatewayReference())
                .isEqualTo("KIN-" + donation.getMerchantReference());
        // Still PENDING, and nothing has reached the ledger: the donor has not paid yet, and a
        // checkout URL is not a receipt.
        assertThat(donation.getStatus()).isEqualTo(DonationStatus.PENDING);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void commitsTheDonationBeforeCallingCyberPay() {
        // The ordering that makes an interrupted initiate recoverable: were the row saved after the
        // call, a crash in between would leave a live checkout page for a donation nobody has any
        // record of, and the donor's money would arrive unattributable.
        givenCheckoutSucceeds();

        donationService.initiate(DONOR_ID, new InitiateDonationRequest(CAMPAIGN_ID, AMOUNT));

        InOrder inOrder = inOrder(donationRepository, cyberPayClient);
        inOrder.verify(donationRepository).save(any());
        inOrder.verify(cyberPayClient).initiatePayment(any(), any(), any(), any(), any());
    }

    @Test
    void sendsCyberPayOurOwnReferenceAndTheCampaignsCurrency() {
        List<Donation> saved = givenCheckoutSucceeds();

        donationService.initiate(DONOR_ID, new InitiateDonationRequest(CAMPAIGN_ID, AMOUNT));

        ArgumentCaptor<String> merchantRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
        verify(cyberPayClient)
                .initiatePayment(merchantRef.capture(), any(), currency.capture(), any(), any());

        assertThat(merchantRef.getValue()).isEqualTo(saved.get(0).getMerchantReference());
        // The campaign's currency, not the caller's — a donation is counted towards the campaign's
        // goal, so a second currency in that total would make the progress figure meaningless.
        assertThat(currency.getValue()).isEqualTo("NGN");
    }

    @Test
    void marksTheDonationFailedWhenCyberPayRefuses() {
        List<Donation> saved = givenCheckoutSucceeds();
        when(cyberPayClient.initiatePayment(any(), any(), any(), any(), any()))
                .thenThrow(ApiException.gatewayUnavailable("down"));

        assertGatewayUnavailable(
                () ->
                        donationService.initiate(
                                DONOR_ID, new InitiateDonationRequest(CAMPAIGN_ID, AMOUNT)));

        // No checkout page exists, so nobody can pay against this row. Closing it keeps the pending
        // list meaning "waiting on a donor" rather than "waiting on nothing".
        assertThat(saved.get(0).getStatus()).isEqualTo(DonationStatus.FAILED);
        assertThat(saved.get(0).getGatewayReference()).isNull();
    }

    @Test
    void refusesToOpenCheckoutForACampaignThatIsNotAccepting() {
        when(campaignService.requireCampaignAcceptingDonations(CAMPAIGN_ID))
                .thenThrow(ApiException.conflict("Campaign 7 is EXPIRED"));

        assertThatThrownBy(
                        () ->
                                donationService.initiate(
                                        DONOR_ID, new InitiateDonationRequest(CAMPAIGN_ID, AMOUNT)))
                .isInstanceOf(ApiException.class);

        // Refused before anything is written, so a closed campaign leaves no debris behind.
        verify(donationRepository, never()).save(any());
        verifyNoInteractions(cyberPayClient);
    }

    @Test
    void merchantReferencesAreUnpredictableAndDistinct() {
        // The reference travels out through the donor's browser and back on the webhook. A
        // sequential one would let anyone enumerate Fonja's donations by counting.
        List<Donation> saved = givenCheckoutSucceeds();

        for (int i = 0; i < 20; i++) {
            donationService.initiate(DONOR_ID, new InitiateDonationRequest(CAMPAIGN_ID, AMOUNT));
        }

        assertThat(saved)
                .hasSize(20)
                .extracting(Donation::getMerchantReference)
                .doesNotHaveDuplicates()
                .allSatisfy(reference -> assertThat(reference).matches("FNJ[0-9a-f]{16}"));
    }

    // --- webhook recovery through MerchantRef ----------------------------------------

    @Test
    void settlesADonationWhoseGatewayReferenceWasNeverStored() {
        // The gap the merchant reference exists to close: initiate reached CyberPay, but the
        // transaction that stores their reference never committed. The donor paid regardless.
        Donation stranded = opened();
        when(donationRepository.findByGatewayReference(REFERENCE)).thenReturn(Optional.empty());
        when(donationRepository.findByMerchantReference(MERCHANT_REF))
                .thenReturn(Optional.of(stranded));
        givenCyberPaySays("Successful", AMOUNT);

        donationService.confirmFromWebhook(REFERENCE, MERCHANT_REF, "Card");

        assertThat(stranded.getStatus()).isEqualTo(DonationStatus.SUCCESS);
        // Backfilled, so every later delivery takes the fast path and the unique index on
        // gateway_reference starts guarding this donation again.
        assertThat(stranded.getGatewayReference()).isEqualTo(REFERENCE);
        verify(ledgerService).recordDonation(stranded);
    }

    @Test
    void ignoresAMerchantReferenceAlreadyBoundToADifferentTransaction() {
        Donation alreadyBound = pending(); // holds REFERENCE
        when(donationRepository.findByGatewayReference("KIN-SOMETHING-ELSE"))
                .thenReturn(Optional.empty());
        when(donationRepository.findByMerchantReference(MERCHANT_REF))
                .thenReturn(Optional.of(alreadyBound));

        donationService.confirmFromWebhook("KIN-SOMETHING-ELSE", MERCHANT_REF, "Card");

        // Two gateway references against one donation is not a gap in our records — it is a replay
        // or a re-used reference, and neither is something to settle.
        verifyNoInteractions(cyberPayClient, ledgerService);
        assertThat(alreadyBound.getGatewayReference()).isEqualTo(REFERENCE);
    }

    // --- helpers ---------------------------------------------------------------------

    private void givenDonation(Donation donation) {
        when(donationRepository.findByGatewayReference(REFERENCE))
                .thenReturn(Optional.of(donation));
    }

    /**
     * Wires the initiate path so every call produces its own donation, the way a real repository
     * would. Returns the list the saved donations land in, newest last.
     */
    private List<Donation> givenCheckoutSucceeds() {
        List<Donation> saved = new ArrayList<>();
        Campaign campaign = campaign();
        User donor = donor();

        when(campaignService.requireCampaignAcceptingDonations(CAMPAIGN_ID)).thenReturn(campaign);
        when(userService.requireById(DONOR_ID)).thenReturn(donor);
        when(donationRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            Donation donation = invocation.getArgument(0);
                            ReflectionTestUtils.setField(
                                    donation, "id", DONATION_ID + saved.size());
                            saved.add(donation);
                            return donation;
                        });
        when(donationRepository.findById(anyLong()))
                .thenAnswer(
                        invocation -> {
                            long id = invocation.getArgument(0);
                            return saved.stream().filter(d -> d.getId() == id).findFirst();
                        });
        when(cyberPayClient.initiatePayment(any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                new CyberPayCheckout(
                                        "KIN-" + invocation.getArgument(0),
                                        "https://payment.cyberpay.ng/pay?ref=x",
                                        null));
        return saved;
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

    /** A donation already through initiate: it holds both references. */
    private Donation pending() {
        Donation donation = opened();
        donation.attachGatewayReference(REFERENCE);
        return donation;
    }

    /** A donation as it exists between the two initiate transactions — ours only, not theirs. */
    private Donation opened() {
        Donation donation = new Donation(campaign(), donor(), AMOUNT, "NGN", MERCHANT_REF);
        ReflectionTestUtils.setField(donation, "id", DONATION_ID);
        return donation;
    }

    private User donor() {
        User donor = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub-1");
        ReflectionTestUtils.setField(donor, "id", DONOR_ID);
        return donor;
    }

    private Campaign campaign() {
        User creator = new User("creator@b.com", "C D", AuthProvider.GOOGLE, "sub-2");
        ReflectionTestUtils.setField(creator, "id", 2L);
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
        ReflectionTestUtils.setField(campaign, "id", CAMPAIGN_ID);
        return campaign;
    }
}
