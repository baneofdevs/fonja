package com.fonja.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.ledger.dto.EarningsResponse;
import com.fonja.ledger.service.LedgerService;
import com.fonja.payout.config.PayoutConfig;
import com.fonja.payout.dto.BankAccountResponse;
import com.fonja.payout.dto.LinkBankAccountRequest;
import com.fonja.payout.dto.PayoutDispatch;
import com.fonja.payout.dto.PayoutInstruction;
import com.fonja.payout.dto.PayoutQuoteResponse;
import com.fonja.payout.dto.PayoutResponse;
import com.fonja.payout.dto.RequestPayoutRequest;
import com.fonja.payout.entity.BankAccount;
import com.fonja.payout.entity.PayoutRequest;
import com.fonja.payout.entity.PayoutStatus;
import com.fonja.payout.repository.BankAccountRepository;
import com.fonja.payout.repository.PayoutRequestRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What these tests are really about is money leaving twice, or leaving and not coming back.
 *
 * <p>The balance arithmetic itself lives in {@code LedgerServiceTest}; here the ledger is a mock
 * and what matters is that {@code PayoutService} calls it at the right moments — reserve on
 * request, reverse on failure, and never on a payout that was refused for some other reason.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayoutServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long PAYOUT_ID = 20L;
    private static final BigDecimal FEE = new BigDecimal("200.00");

    @Mock private PayoutRequestRepository payoutRequestRepository;
    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private LedgerService ledgerService;
    @Mock private UserService userService;
    @Mock private PayoutGateway payoutGateway;

    private PayoutService payoutService;

    @BeforeEach
    void setUp() {
        payoutService =
                new PayoutService(
                        payoutRequestRepository,
                        bankAccountRepository,
                        ledgerService,
                        userService,
                        payoutGateway,
                        new PayoutConfig(),
                        immediateTransactions());

        when(ledgerService.withdrawalFee()).thenReturn(FEE);
        when(userService.requireById(CREATOR_ID)).thenReturn(verifiedCreator());
    }

    // --- Bank account ---------------------------------------------------------------

    @Test
    void linkStoresTheNameTheBankReturnsRatherThanTheOneSubmitted() {
        // The account name is what a transfer is validated against at NIBSS. It is not the
        // creator's to assert, and the request has no field for it precisely so it cannot be.
        when(bankAccountRepository.findByUserId(CREATOR_ID)).thenReturn(Optional.empty());
        when(payoutGateway.lookupAccountName("058", "0123456789"))
                .thenReturn(Optional.of("ADESANYA OLUWATOBI JOSEPH"));
        givenBankAccountSaves();

        BankAccountResponse response = payoutService.linkBankAccount(CREATOR_ID, linkRequest());

        assertThat(response.accountName()).isEqualTo("ADESANYA OLUWATOBI JOSEPH");
        assertThat(response.verified()).isTrue();
    }

    @Test
    void linkSucceedsUnverifiedWhenTheGatewayCannotAnswer() {
        // Name enquiry is a fail-fast convenience, not the last line of defence — the transfer is
        // validated at NIBSS regardless. Blocking every creator because CyberPay is unreachable
        // would be an outage dressed up as a security control.
        when(bankAccountRepository.findByUserId(CREATOR_ID)).thenReturn(Optional.empty());
        when(payoutGateway.lookupAccountName(any(), any())).thenReturn(Optional.empty());
        givenBankAccountSaves();

        BankAccountResponse response = payoutService.linkBankAccount(CREATOR_ID, linkRequest());

        assertThat(response.verified()).isFalse();
        assertThat(response.verifiedAt()).isNull();
    }

    @Test
    void relinkingDropsTheOldVerification() {
        // The stamp belongs to the account number it was obtained for. Carrying it across would let
        // an unverified account inherit a verified link.
        BankAccount existing = verifiedAccount();
        when(bankAccountRepository.findByUserId(CREATOR_ID)).thenReturn(Optional.of(existing));
        when(payoutGateway.lookupAccountName(any(), any())).thenReturn(Optional.empty());

        BankAccountResponse response =
                payoutService.linkBankAccount(
                        CREATOR_ID, new LinkBankAccountRequest("9876543210", "044", "Access Bank"));

        assertThat(response.verified()).isFalse();
        assertThat(existing.getAccountNumber()).isEqualTo("9876543210");
    }

    @Test
    void anUnverifiedUserCannotLinkAnAccount() {
        when(userService.requireById(CREATOR_ID)).thenReturn(unverifiedUser());

        assertThatThrownBy(() -> payoutService.linkBankAccount(CREATOR_ID, linkRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VERIFICATION_REQUIRED);

        verify(bankAccountRepository, never()).save(any());
    }

    @Test
    void theAccountNumberComesBackMasked() {
        when(bankAccountRepository.findByUserId(CREATOR_ID))
                .thenReturn(Optional.of(verifiedAccount()));

        assertThat(payoutService.getBankAccount(CREATOR_ID).accountNumber())
                .isEqualTo("******6789");
    }

    // --- Quote ----------------------------------------------------------------------

    @Test
    void theMaximumWithdrawableIsTheBalanceLessTheFee() {
        // The whole reason this endpoint exists: a creator with 9,300 available cannot withdraw
        // 9,300, because the fee comes out of the same balance. A client that offers "withdraw all"
        // against `available` produces a request that is always refused.
        givenAvailable("9300.00");
        when(bankAccountRepository.findByUserId(CREATOR_ID))
                .thenReturn(Optional.of(verifiedAccount()));

        PayoutQuoteResponse quote = payoutService.quote(CREATOR_ID);

        assertThat(quote.available()).isEqualByComparingTo("9300.00");
        assertThat(quote.maxWithdrawable()).isEqualByComparingTo("9100.00");
        assertThat(quote.canWithdraw()).isTrue();
    }

    @Test
    void aBalanceBelowTheFeeQuotesZeroRatherThanANegativeNumber() {
        givenAvailable("150.00");
        when(bankAccountRepository.findByUserId(CREATOR_ID))
                .thenReturn(Optional.of(verifiedAccount()));

        PayoutQuoteResponse quote = payoutService.quote(CREATOR_ID);

        assertThat(quote.maxWithdrawable()).isEqualByComparingTo("0.00");
        assertThat(quote.canWithdraw()).isFalse();
    }

    @Test
    void aCreatorWithNoLinkedAccountCannotWithdrawEvenWithABalance() {
        givenAvailable("9300.00");
        when(bankAccountRepository.findByUserId(CREATOR_ID)).thenReturn(Optional.empty());

        PayoutQuoteResponse quote = payoutService.quote(CREATOR_ID);

        assertThat(quote.bankAccountLinked()).isFalse();
        assertThat(quote.canWithdraw()).isFalse();
    }

    // --- Request --------------------------------------------------------------------

    @Test
    void requestingReservesTheMoneyImmediately() {
        // Reserved when asked, not when settled — otherwise a creator could queue three withdrawals
        // against one balance while the first is still in flight.
        List<PayoutRequest> saved = givenPayoutSaves();
        when(bankAccountRepository.findByUserId(CREATOR_ID))
                .thenReturn(Optional.of(verifiedAccount()));

        PayoutResponse response =
                payoutService.requestPayout(
                        CREATOR_ID, new RequestPayoutRequest(new BigDecimal("5000.00")));

        assertThat(response.status()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(response.fee()).isEqualByComparingTo(FEE);
        assertThat(response.totalDeducted()).isEqualByComparingTo("5200.00");
        verify(ledgerService).recordWithdrawal(saved.get(0));
    }

    @Test
    void requestingWithoutABankAccountIsRefusedBeforeAnythingIsReserved() {
        when(bankAccountRepository.findByUserId(CREATOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                payoutService.requestPayout(
                                        CREATOR_ID,
                                        new RequestPayoutRequest(new BigDecimal("5000.00"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Link a bank account");

        verify(ledgerService, never()).recordWithdrawal(any());
        verify(payoutRequestRepository, never()).save(any());
    }

    @Test
    void everyRequestGetsItsOwnMerchantReference() {
        List<PayoutRequest> saved = givenPayoutSaves();
        when(bankAccountRepository.findByUserId(CREATOR_ID))
                .thenReturn(Optional.of(verifiedAccount()));

        payoutService.requestPayout(CREATOR_ID, new RequestPayoutRequest(new BigDecimal("100.00")));
        payoutService.requestPayout(CREATOR_ID, new RequestPayoutRequest(new BigDecimal("100.00")));

        assertThat(saved.get(0).getMerchantReference())
                .isNotEqualTo(saved.get(1).getMerchantReference());
        assertThat(saved.get(0).getMerchantReference()).startsWith("FNJW");
    }

    // --- Dispatch -------------------------------------------------------------------

    @Test
    void anAcceptedInstructionMovesThePayoutToProcessing() {
        PayoutRequest payout = requestedPayout();
        givenPayoutById(payout);
        when(payoutGateway.send(any())).thenReturn(PayoutDispatch.accepted("KIN-1"));

        assertThat(payoutService.dispatch(PAYOUT_ID)).isTrue();

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PROCESSING);
        assertThat(payout.getGatewayReference()).isEqualTo("KIN-1");
        // Accepted is not paid. Nothing is reversed, and nothing is confirmed either.
        verify(ledgerService, never()).reverseWithdrawal(any());
    }

    @Test
    void aRefusedInstructionFailsThePayoutAndReturnsTheMoney() {
        // A refusal is definite — the gateway answered — so the reserve goes back at once rather
        // than waiting for a human.
        PayoutRequest payout = requestedPayout();
        givenPayoutById(payout);
        when(payoutGateway.send(any()))
                .thenReturn(PayoutDispatch.refused("We are unable to debit your wallet"));

        assertThat(payoutService.dispatch(PAYOUT_ID)).isFalse();

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureReason()).contains("unable to debit");
        verify(ledgerService).reverseWithdrawal(payout);
    }

    @Test
    void aPayoutIsNeverSentTwice() {
        // The attempt is stamped before the gateway is called, so a payout whose reply was lost is
        // permanently excluded from the automatic path. Re-sending it would pay a creator twice
        // from a balance that was debited once.
        PayoutRequest payout = requestedPayout();
        givenPayoutById(payout);
        when(payoutGateway.send(any()))
                .thenThrow(ApiException.gatewayUnavailable("connection reset"));

        assertThatThrownBy(() -> payoutService.dispatch(PAYOUT_ID))
                .isInstanceOf(ApiException.class);

        assertThat(payout.getDispatchAttemptedAt()).isNotNull();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.REQUESTED);

        // The second run finds it already attempted and does not call out again.
        assertThat(payoutService.dispatch(PAYOUT_ID)).isFalse();
        verify(payoutGateway, org.mockito.Mockito.times(1)).send(any());
    }

    @Test
    void theInstructionCarriesTheBanksNameAndTheCreatorsAmount() {
        PayoutRequest payout = requestedPayout();
        givenPayoutById(payout);
        when(payoutGateway.send(any())).thenReturn(PayoutDispatch.accepted("KIN-1"));

        payoutService.dispatch(PAYOUT_ID);

        ArgumentCaptor<PayoutInstruction> sent = ArgumentCaptor.forClass(PayoutInstruction.class);
        verify(payoutGateway).send(sent.capture());
        // The amount sent is what the creator receives — Fonja's fee is already out of it, and is
        // never something the gateway sees.
        assertThat(sent.getValue().amount()).isEqualByComparingTo("5000.00");
        assertThat(sent.getValue().beneficiaryName()).isEqualTo("ADESANYA OLUWATOBI JOSEPH");
        assertThat(sent.getValue().merchantReference()).isEqualTo(payout.getMerchantReference());
    }

    // --- Settlement -----------------------------------------------------------------

    @Test
    void markingPaidLeavesTheReserveInPlace() {
        PayoutRequest payout = processingPayout();
        givenPayoutById(payout);

        assertThat(payoutService.markPaid(PAYOUT_ID).status()).isEqualTo(PayoutStatus.PAID);

        // The money genuinely left, so there is nothing to compensate.
        verify(ledgerService, never()).reverseWithdrawal(any());
        assertThat(payout.getCompletedAt()).isNotNull();
    }

    @Test
    void markingFailedReturnsTheAmountAndTheFee() {
        PayoutRequest payout = processingPayout();
        givenPayoutById(payout);

        payoutService.markFailed(PAYOUT_ID, "Beneficiary account closed");

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        verify(ledgerService).reverseWithdrawal(payout);
    }

    @Test
    void aPaidPayoutCannotBeFailedAfterwards() {
        // The reversal would return money that has already left. This is the transition that must
        // never be allowed, whatever an admin clicks.
        PayoutRequest payout = processingPayout();
        payout.markPaid();
        givenPayoutById(payout);

        assertThatThrownBy(() -> payoutService.markFailed(PAYOUT_ID, "changed my mind"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);

        verify(ledgerService, never()).reverseWithdrawal(any());
    }

    @Test
    void aFailedPayoutIsNotReversedTwice() {
        PayoutRequest payout = processingPayout();
        givenPayoutById(payout);

        payoutService.markFailed(PAYOUT_ID, "Beneficiary account closed");

        assertThatThrownBy(() -> payoutService.markFailed(PAYOUT_ID, "again"))
                .isInstanceOf(ApiException.class);
        verify(ledgerService, org.mockito.Mockito.times(1)).reverseWithdrawal(payout);
    }

    // --- Fixtures --------------------------------------------------------------------

    /**
     * Runs every callback inline, so the two-transaction dispatch can be tested without a database.
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private LinkBankAccountRequest linkRequest() {
        return new LinkBankAccountRequest("0123456789", "058", "GTBank");
    }

    private User verifiedCreator() {
        User user = new User("creator@fonja.ng", "Adesanya Joseph", AuthProvider.GOOGLE, "sub-1");
        user.markVerified();
        ReflectionTestUtils.setField(user, "id", CREATOR_ID);
        return user;
    }

    private User unverifiedUser() {
        User user = new User("donor@fonja.ng", "A Donor", AuthProvider.GOOGLE, "sub-2");
        ReflectionTestUtils.setField(user, "id", CREATOR_ID);
        return user;
    }

    private BankAccount verifiedAccount() {
        BankAccount account =
                new BankAccount(
                        verifiedCreator(),
                        "ADESANYA OLUWATOBI JOSEPH",
                        "0123456789",
                        "058",
                        "GTBank");
        ReflectionTestUtils.setField(account, "id", 5L);
        account.markVerified("ADESANYA OLUWATOBI JOSEPH");
        return account;
    }

    private PayoutRequest requestedPayout() {
        PayoutRequest payout =
                new PayoutRequest(
                        verifiedCreator(),
                        verifiedAccount(),
                        new BigDecimal("5000.00"),
                        FEE,
                        "FNJW-test");
        ReflectionTestUtils.setField(payout, "id", PAYOUT_ID);
        return payout;
    }

    private PayoutRequest processingPayout() {
        PayoutRequest payout = requestedPayout();
        payout.claimForDispatch();
        payout.markProcessing("KIN-1");
        return payout;
    }

    private void givenAvailable(String available) {
        when(ledgerService.getEarnings(CREATOR_ID))
                .thenReturn(
                        new EarningsResponse(
                                new BigDecimal(available),
                                new BigDecimal(available),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO));
    }

    private void givenPayoutById(PayoutRequest payout) {
        when(payoutRequestRepository.findById(PAYOUT_ID)).thenReturn(Optional.of(payout));
    }

    private void givenBankAccountSaves() {
        when(bankAccountRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            BankAccount account = invocation.getArgument(0);
                            ReflectionTestUtils.setField(account, "id", 5L);
                            return account;
                        });
    }

    private List<PayoutRequest> givenPayoutSaves() {
        List<PayoutRequest> saved = new ArrayList<>();
        when(payoutRequestRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            PayoutRequest payout = invocation.getArgument(0);
                            ReflectionTestUtils.setField(payout, "id", PAYOUT_ID + saved.size());
                            saved.add(payout);
                            return payout;
                        });
        return saved;
    }
}
