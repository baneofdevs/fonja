package com.fonja.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.Category;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.ledger.config.LedgerConfig;
import com.fonja.ledger.dto.EarningsResponse;
import com.fonja.ledger.entity.LedgerEntry;
import com.fonja.ledger.entity.LedgerEntryType;
import com.fonja.ledger.repository.LedgerEntryRepository;
import com.fonja.ledger.repository.LedgerEntryRepository.TypeTotal;
import com.fonja.payout.entity.PayoutRequest;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The ledger's job is arithmetic that must never be wrong, so these tests are mostly about the
 * awkward numbers: splits that do not divide evenly, donations too small to yield a fee, and
 * balances that have to account for a reversed withdrawal.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long DONATION_ID = 10L;
    private static final Long PAYOUT_ID = 20L;

    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private UserService userService;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerEntryRepository, userService, new LedgerConfig());
    }

    // --- Donation split --------------------------------------------------------------

    @Test
    void donationSplitsNinetyThreeSeven() {
        Donation donation = donation(new BigDecimal("10000.00"), DonationStatus.SUCCESS);

        ledgerService.recordDonation(donation);

        Map<LedgerEntryType, BigDecimal> written = capturedEntries();
        assertThat(written.get(LedgerEntryType.DONATION_CREDIT)).isEqualByComparingTo("9300.00");
        assertThat(written.get(LedgerEntryType.PLATFORM_FEE)).isEqualByComparingTo("700.00");
    }

    @Test
    void splitReconcilesExactlyWhenThePercentageDoesNotDivideEvenly() {
        // 7% of 333.33 is 23.3331 — the fee rounds, and the creator's share is what is left, so no
        // fraction of a naira can go missing between the two entries.
        Donation donation = donation(new BigDecimal("333.33"), DonationStatus.SUCCESS);

        ledgerService.recordDonation(donation);

        Map<LedgerEntryType, BigDecimal> written = capturedEntries();
        assertThat(written.get(LedgerEntryType.PLATFORM_FEE)).isEqualByComparingTo("23.33");
        assertThat(written.get(LedgerEntryType.DONATION_CREDIT)).isEqualByComparingTo("310.00");
        assertThat(
                        written.get(LedgerEntryType.DONATION_CREDIT)
                                .add(written.get(LedgerEntryType.PLATFORM_FEE)))
                .isEqualByComparingTo("333.33");
    }

    @Test
    void donationTooSmallToYieldAFeeWritesOnlyTheCredit() {
        // 7% of 0.05 rounds to nothing. A zero-amount entry is rejected by the entity, so the fee
        // leg is skipped rather than written — and the creator still gets the whole amount.
        Donation donation = donation(new BigDecimal("0.05"), DonationStatus.SUCCESS);

        ledgerService.recordDonation(donation);

        Map<LedgerEntryType, BigDecimal> written = capturedEntries();
        assertThat(written).containsOnlyKeys(LedgerEntryType.DONATION_CREDIT);
        assertThat(written.get(LedgerEntryType.DONATION_CREDIT)).isEqualByComparingTo("0.05");
    }

    @Test
    void unconfirmedDonationIsRefused() {
        Donation donation = donation(new BigDecimal("10000.00"), DonationStatus.PENDING);

        assertThatThrownBy(() -> ledgerService.recordDonation(donation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only SUCCESS");

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void alreadyPostedDonationIsIgnored() {
        Donation donation = donation(new BigDecimal("10000.00"), DonationStatus.SUCCESS);
        when(ledgerEntryRepository.existsByDonationId(DONATION_ID)).thenReturn(true);

        ledgerService.recordDonation(donation);

        verify(ledgerEntryRepository, never()).save(any());
    }

    // --- Withdrawal ------------------------------------------------------------------

    @Test
    void withdrawalReservesAmountAndFeeWhenEarningsCover() {
        PayoutRequest request = payoutRequest(new BigDecimal("5000.00"));
        givenBalance(Map.of(LedgerEntryType.DONATION_CREDIT, new BigDecimal("9300.00")));
        when(userService.requireByIdForUpdate(CREATOR_ID)).thenReturn(creator());

        ledgerService.recordWithdrawal(request);

        Map<LedgerEntryType, BigDecimal> written = capturedEntries();
        assertThat(written.get(LedgerEntryType.WITHDRAWAL_DEBIT)).isEqualByComparingTo("5000.00");
        assertThat(written.get(LedgerEntryType.WITHDRAWAL_FEE)).isEqualByComparingTo("200.00");
    }

    @Test
    void withdrawalIsRefusedWhenTheFeeTipsItOverTheBalance() {
        // The creator has exactly the amount they asked for, but not the amount plus the flat fee.
        PayoutRequest request = payoutRequest(new BigDecimal("5000.00"));
        givenBalance(Map.of(LedgerEntryType.DONATION_CREDIT, new BigDecimal("5000.00")));
        when(userService.requireByIdForUpdate(CREATOR_ID)).thenReturn(creator());

        assertThatThrownBy(() -> ledgerService.recordWithdrawal(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("do not cover")
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void alreadyPostedWithdrawalIsIgnored() {
        PayoutRequest request = payoutRequest(new BigDecimal("5000.00"));
        when(ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                        PAYOUT_ID, LedgerEntryType.WITHDRAWAL_DEBIT))
                .thenReturn(true);

        ledgerService.recordWithdrawal(request);

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void reversalReturnsBothTheAmountAndTheFee() {
        PayoutRequest request = payoutRequest(new BigDecimal("5000.00"));
        when(ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                        PAYOUT_ID, LedgerEntryType.WITHDRAWAL_DEBIT))
                .thenReturn(true);
        when(ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                        PAYOUT_ID, LedgerEntryType.WITHDRAWAL_REVERSAL))
                .thenReturn(false);
        when(ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                        PAYOUT_ID, LedgerEntryType.WITHDRAWAL_FEE))
                .thenReturn(true);

        ledgerService.reverseWithdrawal(request);

        Map<LedgerEntryType, BigDecimal> written = capturedEntries();
        assertThat(written.get(LedgerEntryType.WITHDRAWAL_REVERSAL))
                .isEqualByComparingTo("5000.00");
        assertThat(written.get(LedgerEntryType.WITHDRAWAL_FEE_REVERSAL))
                .isEqualByComparingTo("200.00");
    }

    @Test
    void reversingAWithdrawalThatWasNeverPostedIsRefused() {
        PayoutRequest request = payoutRequest(new BigDecimal("5000.00"));

        assertThatThrownBy(() -> ledgerService.reverseWithdrawal(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never posted");
    }

    // --- Balance derivation ----------------------------------------------------------

    @Test
    void balanceIsCreditsMinusDebits() {
        givenBalance(
                Map.of(
                        LedgerEntryType.DONATION_CREDIT, new BigDecimal("9300.00"),
                        LedgerEntryType.WITHDRAWAL_DEBIT, new BigDecimal("5000.00"),
                        LedgerEntryType.WITHDRAWAL_FEE, new BigDecimal("200.00")));

        EarningsResponse earnings = ledgerService.getEarnings(CREATOR_ID);

        assertThat(earnings.available()).isEqualByComparingTo("4100.00");
        assertThat(earnings.totalEarned()).isEqualByComparingTo("9300.00");
        assertThat(earnings.totalWithdrawn()).isEqualByComparingTo("5000.00");
        assertThat(earnings.totalWithdrawalFees()).isEqualByComparingTo("200.00");
    }

    @Test
    void aReversedWithdrawalIsBackInTheBalanceAndOutOfTheTotals() {
        givenBalance(
                Map.of(
                        LedgerEntryType.DONATION_CREDIT, new BigDecimal("9300.00"),
                        LedgerEntryType.WITHDRAWAL_DEBIT, new BigDecimal("5000.00"),
                        LedgerEntryType.WITHDRAWAL_FEE, new BigDecimal("200.00"),
                        LedgerEntryType.WITHDRAWAL_REVERSAL, new BigDecimal("5000.00"),
                        LedgerEntryType.WITHDRAWAL_FEE_REVERSAL, new BigDecimal("200.00")));

        EarningsResponse earnings = ledgerService.getEarnings(CREATOR_ID);

        assertThat(earnings.available()).isEqualByComparingTo("9300.00");
        // The failed attempt stays on the ledger, but it is not money the creator took out.
        assertThat(earnings.totalWithdrawn()).isEqualByComparingTo("0.00");
        assertThat(earnings.totalWithdrawalFees()).isEqualByComparingTo("0.00");
    }

    @Test
    void aCreatorWithNoEntriesHasNothingAvailable() {
        givenBalance(Map.of());

        EarningsResponse earnings = ledgerService.getEarnings(CREATOR_ID);

        assertThat(earnings.available()).isEqualByComparingTo("0.00");
        assertThat(earnings.totalEarned()).isEqualByComparingTo("0.00");
    }

    @Test
    void platformRevenueCountsDonationCutAndWithdrawalFeesAlike() {
        // The withdrawal fee is the half that used to be missed. It is written against the creator,
        // because it has to come out of their balance — so a "where beneficiary is null" query
        // counts the 7% and silently drops every ₦200 of it.
        when(ledgerEntryRepository.sumByTypeForPlatform(anyList()))
                .thenReturn(
                        totals(
                                Map.of(
                                        LedgerEntryType.PLATFORM_FEE, new BigDecimal("700.00"),
                                        LedgerEntryType.WITHDRAWAL_FEE, new BigDecimal("600.00"))));

        assertThat(ledgerService.getPlatformRevenue()).isEqualByComparingTo("1300.00");
    }

    @Test
    void platformRevenueGivesBackTheFeeOnAReversedWithdrawal() {
        // Fonja does not keep the flat fee on a failure, so a reversal has to come off revenue —
        // and it is a CREDIT to the creator, which the generic net would have added here instead.
        when(ledgerEntryRepository.sumByTypeForPlatform(anyList()))
                .thenReturn(
                        totals(
                                Map.of(
                                        LedgerEntryType.PLATFORM_FEE,
                                        new BigDecimal("700.00"),
                                        LedgerEntryType.WITHDRAWAL_FEE,
                                        new BigDecimal("600.00"),
                                        LedgerEntryType.WITHDRAWAL_FEE_REVERSAL,
                                        new BigDecimal("200.00"))));

        assertThat(ledgerService.getPlatformRevenue()).isEqualByComparingTo("1100.00");
    }

    // --- Fixtures --------------------------------------------------------------------

    private User creator() {
        User user = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub-1");
        ReflectionTestUtils.setField(user, "id", CREATOR_ID);
        return user;
    }

    private Donation donation(BigDecimal amount, DonationStatus status) {
        User creator = creator();
        Campaign campaign =
                new Campaign(
                        creator,
                        "Title",
                        "Story",
                        new Category("medical-health", "Medical & Health", null, 120),
                        null,
                        BeneficiaryType.SELF,
                        "Nigeria",
                        null,
                        BigDecimal.valueOf(500_000),
                        "NGN",
                        false,
                        "cover.jpg",
                        null);
        Donation donation = new Donation(campaign, creator, amount, "NGN", "CYB-1");
        ReflectionTestUtils.setField(donation, "id", DONATION_ID);
        ReflectionTestUtils.setField(donation, "status", status);
        return donation;
    }

    private PayoutRequest payoutRequest(BigDecimal amount) {
        PayoutRequest request =
                new PayoutRequest(creator(), null, amount, new BigDecimal("200.00"), "FNJW-test");
        ReflectionTestUtils.setField(request, "id", PAYOUT_ID);
        return request;
    }

    private void givenBalance(Map<LedgerEntryType, BigDecimal> byType) {
        when(ledgerEntryRepository.sumByTypeForBeneficiary(CREATOR_ID)).thenReturn(totals(byType));
    }

    private List<TypeTotal> totals(Map<LedgerEntryType, BigDecimal> byType) {
        List<TypeTotal> rows = new ArrayList<>();
        byType.forEach(
                (type, total) ->
                        rows.add(
                                new TypeTotal() {
                                    @Override
                                    public LedgerEntryType getEntryType() {
                                        return type;
                                    }

                                    @Override
                                    public BigDecimal getTotal() {
                                        return total;
                                    }
                                }));
        return rows;
    }

    /** Every entry the service saved, keyed by type — no type is ever written twice in one call. */
    private Map<LedgerEntryType, BigDecimal> capturedEntries() {
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());

        Map<LedgerEntryType, BigDecimal> byType = new EnumMap<>(LedgerEntryType.class);
        for (LedgerEntry entry : captor.getAllValues()) {
            assertThat(byType).doesNotContainKey(entry.getEntryType());
            byType.put(entry.getEntryType(), entry.getAmount());
        }
        return byType;
    }
}
