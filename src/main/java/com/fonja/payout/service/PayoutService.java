package com.fonja.payout.service;

import com.fonja.common.exception.ApiException;
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
import com.fonja.user.entity.Role;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payout module's entry point: linking an account, reserving a withdrawal, and settling it.
 *
 * <p><strong>The money never moves here.</strong> Every balance change is a {@link LedgerService}
 * call — this class decides <em>whether</em> a withdrawal may happen and records what became of it,
 * and the ledger remains the only writer of entries. The two rules that follow from that are worth
 * stating: the reserve is written when a payout is <em>requested</em>, not when it settles, so a
 * creator cannot queue three withdrawals against one balance; and the reserve is released only by a
 * FAILED payout, through a compensating pair rather than a deletion.
 *
 * <p>No per-withdrawal admin approval — technical spec §4.6. Verification happened once, at creator
 * onboarding, and gating every withdrawal on a second human would make the product unusable without
 * making it safer.
 */
@Service
@EnableConfigurationProperties(PayoutConfig.class)
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    /** As on a donation: 64 bits of randomness, unguessable and short enough for any gateway. */
    private static final int REFERENCE_RANDOM_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The statuses an admin still has something to do about. */
    private static final List<PayoutStatus> OPEN_STATUSES =
            List.of(PayoutStatus.REQUESTED, PayoutStatus.PROCESSING);

    private final PayoutRequestRepository payoutRequestRepository;
    private final BankAccountRepository bankAccountRepository;
    private final LedgerService ledgerService;
    private final UserService userService;
    private final PayoutGateway payoutGateway;
    private final PayoutConfig config;
    private final TransactionTemplate transactionTemplate;

    public PayoutService(
            PayoutRequestRepository payoutRequestRepository,
            BankAccountRepository bankAccountRepository,
            LedgerService ledgerService,
            UserService userService,
            PayoutGateway payoutGateway,
            PayoutConfig config,
            TransactionTemplate transactionTemplate) {
        this.payoutRequestRepository = payoutRequestRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.ledgerService = ledgerService;
        this.userService = userService;
        this.payoutGateway = payoutGateway;
        this.config = config;
        this.transactionTemplate = transactionTemplate;
    }

    // --- Bank account ---------------------------------------------------------------

    /**
     * Links or relinks the account a creator's withdrawals go to.
     *
     * <p>The name is taken from the bank, never from the request. When CyberPay's name enquiry
     * answers, the holder it reports is stored and the account is marked verified; when it does not,
     * the account links anyway with a placeholder name and no verification stamp.
     *
     * <p>Linking soft is deliberate. Name enquiry is a fail-fast convenience, not the last line of
     * defence — a Nigerian transfer is validated against the account at NIBSS whether or not we
     * looked it up first, and a mismatched one fails there and reverses. Blocking every creator from
     * linking an account because a third party is unreachable would be an outage dressed up as a
     * security control.
     *
     * @throws ApiException forbidden if the caller is not a verified creator
     */
    @Transactional
    public BankAccountResponse linkBankAccount(Long userId, LinkBankAccountRequest request) {
        User creator = requireVerifiedCreator(userId);

        Optional<String> confirmedName =
                payoutGateway.lookupAccountName(request.bankCode(), request.accountNumber());

        BankAccount account =
                bankAccountRepository
                        .findByUserId(userId)
                        .map(
                                existing -> {
                                    existing.relink(
                                            confirmedName.orElse(creator.getFullName()),
                                            request.accountNumber(),
                                            request.bankCode(),
                                            request.bankName());
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        bankAccountRepository.save(
                                                new BankAccount(
                                                        creator,
                                                        confirmedName.orElse(creator.getFullName()),
                                                        request.accountNumber(),
                                                        request.bankCode(),
                                                        request.bankName())));

        confirmedName.ifPresent(account::markVerified);
        if (confirmedName.isEmpty()) {
            log.warn(
                    "Bank account for user id={} linked without a name enquiry (gateway available={})",
                    userId,
                    payoutGateway.isAvailable());
        }

        log.info("User id={} linked a bank account at bankCode={}", userId, request.bankCode());
        return BankAccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public BankAccountResponse getBankAccount(Long userId) {
        return BankAccountResponse.from(requireBankAccount(userId));
    }

    // --- Withdrawal -----------------------------------------------------------------

    /**
     * What the creator could withdraw right now.
     *
     * <p>{@code maxWithdrawable} is the figure that matters and the one a client would otherwise get
     * wrong: the flat fee comes out of the same balance, so a creator with ₦9,300 available can ask
     * for at most ₦9,100.
     */
    @Transactional(readOnly = true)
    public PayoutQuoteResponse quote(Long userId) {
        BigDecimal available = ledgerService.getEarnings(userId).available();
        BigDecimal fee = ledgerService.withdrawalFee();
        BigDecimal max = available.subtract(fee).max(BigDecimal.ZERO);
        boolean linked = bankAccountRepository.findByUserId(userId).isPresent();

        return new PayoutQuoteResponse(available, fee, max, linked && max.signum() > 0, linked);
    }

    /**
     * Reserves a withdrawal against the creator's earnings.
     *
     * <p>One transaction on purpose, unlike a donation's initiate: the payout row and the ledger
     * reserve must both exist or neither must. A row without a reserve is a withdrawal the creator
     * has not paid for, and a reserve without a row is money missing from a balance with nothing to
     * explain it. Nothing here calls the gateway, so there is no external call to keep out of the
     * transaction — dispatch happens afterwards, in {@link #dispatch}.
     *
     * <p>The balance check itself lives in {@link LedgerService#recordWithdrawal}, which takes a row
     * lock on the creator first. That is what serialises two withdrawal requests arriving at once;
     * checking the balance here as well would just be a check that a concurrent request could pass.
     *
     * @throws ApiException forbidden if the caller is not a verified creator, badRequest if no bank
     *     account is linked, conflict if the balance will not cover the amount plus the fee
     */
    @Transactional
    public PayoutResponse requestPayout(Long userId, RequestPayoutRequest request) {
        User creator = requireVerifiedCreator(userId);
        BankAccount account = requireBankAccount(userId);

        PayoutRequest payout =
                payoutRequestRepository.save(
                        new PayoutRequest(
                                creator,
                                account,
                                request.amount(),
                                ledgerService.withdrawalFee(),
                                newMerchantReference()));

        ledgerService.recordWithdrawal(payout);

        log.info(
                "Payout id={} requested by creator id={} for {} (merchantRef={})",
                payout.getId(),
                userId,
                payout.getAmount(),
                payout.getMerchantReference());
        return PayoutResponse.from(payout);
    }

    @Transactional(readOnly = true)
    public Page<PayoutResponse> myPayouts(Long userId, Pageable pageable) {
        return payoutRequestRepository
                .findByCreatorIdOrderByRequestedAtDesc(userId, pageable)
                .map(PayoutResponse::from);
    }

    // --- Settlement -----------------------------------------------------------------

    /**
     * Sends one payout to the gateway.
     *
     * <p><strong>Deliberately not one transaction.</strong> The attempt is stamped and committed
     * before the call, so a payout is sent at most once even if the reply never arrives — see {@link
     * PayoutRequest#claimForDispatch()}. The outcome is then recorded in a second transaction.
     * Wrapping both around the HTTP call would mean a commit failure after CyberPay accepted the
     * instruction leaves money gone with no record of where it went.
     *
     * @return true if the gateway accepted the instruction
     * @throws ApiException gatewayUnavailable if the call could not be completed — the payout stays
     *     REQUESTED, is never retried automatically, and needs a human to check with CyberPay
     */
    public boolean dispatch(Long payoutId) {
        PayoutInstruction instruction =
                transactionTemplate.execute(
                        status -> {
                            PayoutRequest payout = require(payoutId);
                            if (!payout.claimForDispatch()) {
                                log.debug(
                                        "Payout id={} is {} and already attempted; not dispatching",
                                        payoutId,
                                        payout.getStatus());
                                return null;
                            }
                            return instructionFor(payout);
                        });

        if (instruction == null) {
            return false;
        }

        PayoutDispatch result = payoutGateway.send(instruction);

        return Boolean.TRUE.equals(
                transactionTemplate.execute(
                        status -> {
                            PayoutRequest payout = require(payoutId);
                            if (result.accepted()) {
                                payout.markProcessing(result.gatewayReference());
                                log.info(
                                        "Payout id={} is with the gateway as reference={}",
                                        payoutId,
                                        result.gatewayReference());
                                return true;
                            }
                            // A refusal is definite — the gateway answered and declined — so the
                            // reserve goes straight back rather than waiting for anyone.
                            fail(payout, result.message());
                            return false;
                        }));
    }

    /**
     * Marks a payout paid. Admin-only, and the manual counterpart to a gateway confirmation.
     *
     * <p>The reserve simply stands: it was written when the payout was requested and the money has
     * now genuinely left, so there is nothing to compensate.
     *
     * @throws ApiException conflict if the payout has already reached a terminal state
     */
    @Transactional
    public PayoutResponse markPaid(Long payoutId) {
        PayoutRequest payout = require(payoutId);
        if (payout.isTerminal()) {
            throw ApiException.conflict(
                    "Payout " + payoutId + " is already " + payout.getStatus());
        }
        payout.markPaid();
        log.info("Payout id={} marked PAID for creator id={}", payoutId, payout.getCreator().getId());
        return PayoutResponse.from(payout);
    }

    /**
     * Marks a payout failed and returns the reserved amount and fee to the creator.
     *
     * @throws ApiException conflict if the payout has already reached a terminal state
     */
    @Transactional
    public PayoutResponse markFailed(Long payoutId, String reason) {
        PayoutRequest payout = require(payoutId);
        if (payout.isTerminal()) {
            throw ApiException.conflict(
                    "Payout " + payoutId + " is already " + payout.getStatus());
        }
        fail(payout, reason);
        return PayoutResponse.from(payout);
    }

    /** Everything an admin still has to resolve, oldest first. */
    @Transactional(readOnly = true)
    public Page<PayoutResponse> openPayouts(Pageable pageable) {
        return payoutRequestRepository
                .findByStatusInOrderByRequestedAtAsc(OPEN_STATUSES, pageable)
                .map(PayoutResponse::from);
    }

    // --- Internals ------------------------------------------------------------------

    /**
     * The single place a payout is failed, so the status change and the ledger reversal can never
     * happen apart. A FAILED payout whose entries were not reversed is money the creator has lost.
     */
    private void fail(PayoutRequest payout, String reason) {
        payout.markFailed(reason);
        ledgerService.reverseWithdrawal(payout);
        log.warn(
                "Payout id={} FAILED and {} returned to creator id={}: {}",
                payout.getId(),
                payout.getTotalReserved(),
                payout.getCreator().getId(),
                reason);
    }

    private PayoutInstruction instructionFor(PayoutRequest payout) {
        BankAccount account = payout.getBankAccount();
        if (account == null) {
            // Only reachable if the account was deleted out from under an open payout. Refusing to
            // guess a destination is the only safe answer.
            throw ApiException.conflict(
                    "Payout " + payout.getId() + " has no bank account to pay out to");
        }
        return new PayoutInstruction(
                payout.getMerchantReference(),
                payout.getAmount(),
                account.getAccountNumber(),
                account.getBankCode(),
                account.getAccountName(),
                "Fonja withdrawal " + payout.getMerchantReference());
    }

    private PayoutRequest require(Long payoutId) {
        return payoutRequestRepository
                .findById(payoutId)
                .orElseThrow(() -> ApiException.notFound("Payout " + payoutId + " not found"));
    }

    private BankAccount requireBankAccount(Long userId) {
        return bankAccountRepository
                .findByUserId(userId)
                .orElseThrow(
                        () ->
                                ApiException.badRequest(
                                        "Link a bank account before requesting a withdrawal"));
    }

    /**
     * Withdrawing needs both halves of creator status. {@code verified} is what the verification
     * module sets and {@link Role#CREATOR} is what it grants, and they are set together — checking
     * both means a manually edited row cannot produce a creator who can take money out without ever
     * having been verified.
     */
    private User requireVerifiedCreator(Long userId) {
        User user = userService.requireById(userId);
        if (!user.isVerified() || !user.hasRole(Role.CREATOR)) {
            throw ApiException.verificationRequired(
                    "Complete creator verification before linking an account or withdrawing");
        }
        return user;
    }

    /** Random rather than sequential, for the same reason a donation's reference is. */
    private static String newMerchantReference() {
        byte[] bytes = new byte[REFERENCE_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return "FNJW" + HexFormat.of().formatHex(bytes);
    }

    /** Whether automatic dispatch is possible at all, for the job and for startup logging. */
    boolean canDispatchAutomatically() {
        return payoutGateway.isAvailable();
    }

    /** Payouts above this are left for a human even when the gateway is available. */
    BigDecimal maxAutomaticAmount() {
        return config.getMaxAutomaticAmount();
    }
}
