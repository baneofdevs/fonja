package com.fonja.ledger.service;

import com.fonja.common.exception.ApiException;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.ledger.config.LedgerConfig;
import com.fonja.ledger.dto.EarningsResponse;
import com.fonja.ledger.entity.LedgerEntry;
import com.fonja.ledger.entity.LedgerEntryType;
import com.fonja.ledger.repository.LedgerEntryRepository;
import com.fonja.ledger.repository.LedgerEntryRepository.TypeTotal;
import com.fonja.payout.entity.PayoutRequest;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code ledger_entries}, and the only place a balance is worked out.
 *
 * <p>Two rules govern everything here, both from the technical spec's Money-Path Rigor section. The
 * ledger is append-only — nothing is ever updated or deleted, and a mistake is corrected by writing
 * a compensating entry. And no balance is ever stored: {@link #getEarnings} sums the entries every
 * time it is asked. A cached balance column is the single most common way a ledger and reality
 * drift apart, so there deliberately isn't one to drift.
 *
 * <p><strong>Currency:</strong> entries carry no currency, because launch is NGN-only. If Fonja
 * ever takes donations in a second currency, every sum in this class silently becomes wrong — the
 * ledger needs a currency column and per-currency balances before that happens, not after.
 */
@Service
@EnableConfigurationProperties(LedgerConfig.class)
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;

    /**
     * The entry types that are Fonja's income, and the one that refunds it. Listed here rather than
     * derived, because there is no property of an entry that identifies platform revenue — a
     * withdrawal fee is written against the creator exactly like the debit beside it.
     */
    private static final List<LedgerEntryType> PLATFORM_REVENUE_TYPES =
            List.of(
                    LedgerEntryType.PLATFORM_FEE,
                    LedgerEntryType.WITHDRAWAL_FEE,
                    LedgerEntryType.WITHDRAWAL_FEE_REVERSAL);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserService userService;
    private final LedgerConfig config;

    public LedgerService(
            LedgerEntryRepository ledgerEntryRepository,
            UserService userService,
            LedgerConfig config) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userService = userService;
        this.config = config;
    }

    /**
     * Splits a confirmed donation into the creator's credit and Fonja's fee, as one atomic pair.
     *
     * <p>Called from the donation module once CyberPay has been re-queried and the payment
     * confirmed — never straight off a webhook body. Safe to call twice: a webhook retry that gets
     * past the donation module's own guard finds entries already written and does nothing.
     */
    @Transactional
    public void recordDonation(Donation donation) {
        if (donation.getId() == null) {
            throw new IllegalArgumentException(
                    "Donation must be persisted before it can be posted");
        }
        if (donation.getStatus() != DonationStatus.SUCCESS) {
            // Crediting an unconfirmed payment is the worst failure this module has: the creator
            // can
            // withdraw money that never arrived. Refuse rather than trust the caller.
            throw new IllegalStateException(
                    "Donation "
                            + donation.getId()
                            + " is "
                            + donation.getStatus()
                            + ", only SUCCESS may be posted to the ledger");
        }
        if (ledgerEntryRepository.existsByDonationId(donation.getId())) {
            log.info("Donation id={} already posted to the ledger, skipping", donation.getId());
            return;
        }

        User creator = donation.getCampaign().getCreator();
        BigDecimal gross = donation.getAmount();
        BigDecimal platformFee = platformFeeOn(gross);
        BigDecimal creatorShare = gross.subtract(platformFee);

        // The split is derived by subtraction precisely so this holds for every input: rounding the
        // fee and rounding the share independently would leak fractions of a naira on some amounts.
        if (creatorShare.add(platformFee).compareTo(gross) != 0) {
            throw new IllegalStateException("Split does not reconcile against gross " + gross);
        }
        if (creatorShare.signum() <= 0) {
            throw ApiException.badRequest(
                    "Donation of " + gross + " is too small to leave the creator anything");
        }

        ledgerEntryRepository.save(LedgerEntry.donationCredit(creator, donation, creatorShare));
        // A donation small enough that 7% rounds to nothing leaves Fonja no revenue to record, and
        // a
        // zero-amount entry is rejected by the entity. Skipping it keeps the pair reconciling.
        if (platformFee.signum() > 0) {
            ledgerEntryRepository.save(LedgerEntry.platformFee(donation, platformFee));
        }

        log.info(
                "Donation id={} posted: creator id={} credited {}, platform fee {}",
                donation.getId(),
                creator.getId(),
                creatorShare,
                platformFee);
    }

    /**
     * Reserves a withdrawal against a creator's available earnings.
     *
     * <p>Written when the request is made rather than when the money settles, so a creator cannot
     * queue several withdrawals against the same balance while the first is still in flight. The
     * beneficiary row is locked for the duration, which serialises two requests arriving at once —
     * without it both would read the same balance and both would pass.
     *
     * @throws ApiException conflict if the balance will not cover the amount plus the fee
     */
    @Transactional
    public void recordWithdrawal(PayoutRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException(
                    "Payout request must be persisted before it is posted");
        }
        if (ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                request.getId(), LedgerEntryType.WITHDRAWAL_DEBIT)) {
            log.info(
                    "Payout request id={} already posted to the ledger, skipping", request.getId());
            return;
        }

        User creator = userService.requireByIdForUpdate(request.getCreator().getId());
        BigDecimal amount = request.getAmount();
        BigDecimal fee = request.getFee();
        BigDecimal total = amount.add(fee);
        BigDecimal available = availableFor(creator.getId());

        if (available.compareTo(total) < 0) {
            throw ApiException.conflict(
                    "Available earnings "
                            + available
                            + " do not cover a withdrawal of "
                            + amount
                            + " plus a "
                            + fee
                            + " fee");
        }

        ledgerEntryRepository.save(
                LedgerEntry.withdrawal(creator, request, LedgerEntryType.WITHDRAWAL_DEBIT, amount));
        if (fee.signum() > 0) {
            ledgerEntryRepository.save(
                    LedgerEntry.withdrawal(creator, request, LedgerEntryType.WITHDRAWAL_FEE, fee));
        }

        log.info(
                "Payout request id={} reserved {} plus {} fee against creator id={} (available was {})",
                request.getId(),
                amount,
                fee,
                creator.getId(),
                available);
    }

    /**
     * Releases a withdrawal that failed at the gateway, returning the amount and the fee to the
     * creator's balance.
     *
     * <p>A compensating pair, not a deletion — the original debit stays on the ledger, so the
     * record shows that a withdrawal was attempted and reversed rather than pretending it never
     * happened. Fonja does not keep the flat fee on a failure.
     */
    @Transactional
    public void reverseWithdrawal(PayoutRequest request) {
        if (!ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                request.getId(), LedgerEntryType.WITHDRAWAL_DEBIT)) {
            throw new IllegalStateException(
                    "Payout request " + request.getId() + " was never posted, nothing to reverse");
        }
        if (ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                request.getId(), LedgerEntryType.WITHDRAWAL_REVERSAL)) {
            log.info("Payout request id={} already reversed, skipping", request.getId());
            return;
        }

        User creator = request.getCreator();
        ledgerEntryRepository.save(
                LedgerEntry.withdrawal(
                        creator,
                        request,
                        LedgerEntryType.WITHDRAWAL_REVERSAL,
                        request.getAmount()));
        if (ledgerEntryRepository.existsByPayoutRequestIdAndEntryType(
                request.getId(), LedgerEntryType.WITHDRAWAL_FEE)) {
            ledgerEntryRepository.save(
                    LedgerEntry.withdrawal(
                            creator,
                            request,
                            LedgerEntryType.WITHDRAWAL_FEE_REVERSAL,
                            request.getFee()));
        }

        log.info(
                "Payout request id={} reversed, {} returned to creator id={}",
                request.getId(),
                request.getAmount().add(request.getFee()),
                creator.getId());
    }

    /** A creator's earnings, derived from their entries on every call. */
    @Transactional(readOnly = true)
    public EarningsResponse getEarnings(Long creatorId) {
        Map<LedgerEntryType, BigDecimal> totals =
                totalsByType(ledgerEntryRepository.sumByTypeForBeneficiary(creatorId));

        return new EarningsResponse(
                netOf(totals),
                total(totals, LedgerEntryType.DONATION_CREDIT),
                total(totals, LedgerEntryType.WITHDRAWAL_DEBIT)
                        .subtract(total(totals, LedgerEntryType.WITHDRAWAL_REVERSAL)),
                total(totals, LedgerEntryType.WITHDRAWAL_FEE)
                        .subtract(total(totals, LedgerEntryType.WITHDRAWAL_FEE_REVERSAL)));
    }

    /**
     * Fonja's own revenue: the 7% cut of donations plus the flat fee on withdrawals, net of the
     * fees refunded on failed ones.
     *
     * <p>Summed explicitly rather than through {@link #netOf}, because {@link LedgerEntryType}'s
     * direction is written from the <em>beneficiary's</em> point of view and both fee types are
     * inverted from Fonja's. A withdrawal fee is a DEBIT — it leaves the creator's balance — and it
     * is income here; its reversal is a CREDIT to them and a deduction here. Folding these with the
     * generic net would subtract Fonja's revenue instead of adding it.
     */
    @Transactional(readOnly = true)
    public BigDecimal getPlatformRevenue() {
        Map<LedgerEntryType, BigDecimal> totals =
                totalsByType(ledgerEntryRepository.sumByTypeForPlatform(PLATFORM_REVENUE_TYPES));

        return total(totals, LedgerEntryType.PLATFORM_FEE)
                .add(total(totals, LedgerEntryType.WITHDRAWAL_FEE))
                .subtract(total(totals, LedgerEntryType.WITHDRAWAL_FEE_REVERSAL));
    }

    /** Fonja's cut of a gross amount, rounded to the currency's smallest unit. */
    public BigDecimal platformFeeOn(BigDecimal gross) {
        return gross.multiply(config.getPlatformFeePercent())
                .divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** The flat fee a withdrawal will be charged, so callers can show it before committing. */
    public BigDecimal withdrawalFee() {
        return config.getWithdrawalFee();
    }

    private BigDecimal availableFor(Long creatorId) {
        return netOf(totalsByType(ledgerEntryRepository.sumByTypeForBeneficiary(creatorId)));
    }

    private Map<LedgerEntryType, BigDecimal> totalsByType(List<TypeTotal> rows) {
        Map<LedgerEntryType, BigDecimal> totals = new EnumMap<>(LedgerEntryType.class);
        for (TypeTotal row : rows) {
            totals.put(row.getEntryType(), row.getTotal());
        }
        return totals;
    }

    // Credits minus debits. Direction comes from the enum, so a new entry type is counted correctly
    // the moment it is declared — there is no second list here to forget to update.
    private BigDecimal netOf(Map<LedgerEntryType, BigDecimal> totals) {
        BigDecimal net = BigDecimal.ZERO;
        for (Map.Entry<LedgerEntryType, BigDecimal> entry : totals.entrySet()) {
            net =
                    entry.getKey().isCredit()
                            ? net.add(entry.getValue())
                            : net.subtract(entry.getValue());
        }
        return net.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal total(Map<LedgerEntryType, BigDecimal> totals, LedgerEntryType type) {
        return totals.getOrDefault(type, BigDecimal.ZERO)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
