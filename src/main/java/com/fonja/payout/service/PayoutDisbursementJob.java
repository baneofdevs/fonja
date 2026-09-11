package com.fonja.payout.service;

import com.fonja.payout.entity.PayoutRequest;
import com.fonja.payout.entity.PayoutStatus;
import com.fonja.payout.repository.PayoutRequestRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends REQUESTED payouts to the gateway.
 *
 * <p>A job rather than a call inside the withdrawal request, so a creator's request never fails
 * because CyberPay is briefly down: the money is reserved the moment they ask, and the transfer
 * goes out when it can. It also keeps a slow gateway off the request thread.
 *
 * <p><strong>At most one attempt per payout, ever.</strong> {@link
 * PayoutRequestRepository#findTop50ByStatusAndDispatchAttemptedAtIsNullOrderByRequestedAtAsc} only
 * returns payouts nothing has been sent for, and {@link PayoutService#dispatch} stamps the attempt
 * before it calls out. An instruction that times out therefore stays REQUESTED and is never picked
 * up again — because nothing on this side can tell whether CyberPay accepted it, and re-sending
 * would pay the creator twice from a balance that was only debited once. Those rows are counted and
 * warned about below; resolving one means asking CyberPay what happened, not clicking retry.
 *
 * <p><b>Caveat:</b> like the other schedules here, unsynchronised and in-process. Two instances
 * would both sweep, and unlike the donation sweep that is <em>not</em> merely wasteful — two
 * dispatches of the same payout is a double payment. The claim-and-commit in {@code dispatch}
 * narrows the window to the gap between two transactions rather than closing it. <strong>This job
 * must be pinned to a single instance, or take a shared lock, before Fonja runs more than one
 * replica.</strong>
 */
@Component
public class PayoutDisbursementJob {

    private static final Logger log = LoggerFactory.getLogger(PayoutDisbursementJob.class);

    private final PayoutRequestRepository payoutRequestRepository;
    private final PayoutService payoutService;

    public PayoutDisbursementJob(
            PayoutRequestRepository payoutRequestRepository, PayoutService payoutService) {
        this.payoutRequestRepository = payoutRequestRepository;
        this.payoutService = payoutService;
    }

    /**
     * Every five minutes. Frequent enough that a withdrawal feels prompt, infrequent enough that a
     * gateway outage is not hammered — and the delay costs the creator nothing, since their balance
     * was debited when they asked.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void dispatchRequestedPayouts() {
        if (!payoutService.canDispatchAutomatically()) {
            // Not an error. Withdrawals still work; they wait for an admin instead. Logged at debug
            // so an unconfigured environment does not print a line every five minutes forever.
            log.debug("Payout gateway unavailable, skipping dispatch");
            warnAboutUnresolvedAttempts();
            return;
        }

        List<PayoutRequest> due =
                payoutRequestRepository
                        .findTop50ByStatusAndDispatchAttemptedAtIsNullOrderByRequestedAtAsc(
                                PayoutStatus.REQUESTED);
        if (!due.isEmpty()) {
            log.info("Dispatching {} payout(s)", due.size());
            dispatch(due);
        }

        warnAboutUnresolvedAttempts();
    }

    private void dispatch(List<PayoutRequest> due) {
        BigDecimal ceiling = payoutService.maxAutomaticAmount();
        int sent = 0;
        int held = 0;

        for (PayoutRequest payout : due) {
            if (ceiling.signum() > 0 && payout.getAmount().compareTo(ceiling) > 0) {
                // A bug that drains the wallet does it one instruction at a time, and this is what
                // keeps each one small. The payout is untouched and an admin settles it by hand.
                log.warn(
                        "Payout id={} of {} exceeds the automatic ceiling of {}; left for an admin",
                        payout.getId(),
                        payout.getAmount(),
                        ceiling);
                held++;
                continue;
            }
            try {
                if (payoutService.dispatch(payout.getId())) {
                    sent++;
                }
            } catch (RuntimeException e) {
                // The instruction's fate is unknown. The attempt is already stamped, so this payout
                // will not be picked up again; carrying on means one unreachable call does not
                // block
                // the forty-nine behind it.
                log.error(
                        "Payout id={} could not be dispatched and will NOT be retried "
                                + "automatically — confirm with CyberPay whether merchantRef={} was "
                                + "accepted before settling it: {}",
                        payout.getId(),
                        payout.getMerchantReference(),
                        e.toString());
            }
        }

        log.info("Payout dispatch finished: {} sent, {} held for review", sent, held);
    }

    private void warnAboutUnresolvedAttempts() {
        long unresolved =
                payoutRequestRepository.countByStatusAndDispatchAttemptedAtIsNotNull(
                        PayoutStatus.REQUESTED);
        if (unresolved > 0) {
            // A count rather than a list: these need a person, and printing the same rows every
            // five
            // minutes would bury the number rather than surface it.
            log.warn(
                    "{} payout(s) were sent to the gateway with no answer and cannot be retried "
                            + "automatically — check each against CyberPay and settle it manually",
                    unresolved);
        }
    }
}
