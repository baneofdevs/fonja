package com.fonja.donation.service;

import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.donation.service.DonationService.SettlementOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-queries donations that have been PENDING too long, and settles the ones CyberPay has since
 * decided about.
 *
 * <p><strong>This is a money-recovery job, not a cleanup job.</strong> A webhook can be lost — the
 * tunnel was down, the instance was restarting mid-deploy, our handler had a bug, CyberPay gave up
 * after 72 hours of 503s. Every one of those leaves a donation that a donor paid for and a creator
 * was never credited with. Nothing else in the system would ever notice. The abandoned-checkout
 * rows it also closes are a side effect, and much the less important half.
 *
 * <p>It never fails a donation on age or silence. The only thing that closes a row here is CyberPay
 * saying so, because {@link DonationService#confirmFromWebhook} ignores notifications for an
 * already-FAILED donation: guessing wrong would turn a slow payment into a permanently lost one.
 *
 * <p><b>Caveat:</b> like {@code CampaignExpiryJob}, an unsynchronised in-process schedule. On more
 * than one instance every instance sweeps. That is safe rather than merely tolerable — settlement
 * runs in a transaction and the ledger's unique indexes reject a second write — but it is
 * duplicated work against CyberPay's API, so this wants a shared lock before Fonja scales out.
 */
@Component
public class PendingDonationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(PendingDonationSweepJob.class);

    /**
     * How long a donation may sit PENDING before the sweep asks about it. Hosted checkout is a
     * matter of minutes and a webhook arrives seconds after payment, so anything past this is
     * already off the happy path — while a shorter window would mostly re-query donors who are
     * still typing their card number.
     */
    private static final Duration SETTLE_GRACE = Duration.ofMinutes(30);

    /**
     * How far back the sweep keeps trying. CyberPay's own retries stop after 72 hours and their
     * status for an unpaid checkout becomes Abandoned well before this, so a donation still PENDING
     * after a week is not waiting on anything the sweep can provoke — it is waiting on a person.
     * Re-querying it hourly forever would be a cost that grows with the table and resolves nothing.
     */
    private static final Duration GIVE_UP_AFTER = Duration.ofDays(7);

    private final DonationRepository donationRepository;
    private final DonationService donationService;

    public PendingDonationSweepJob(
            DonationRepository donationRepository, DonationService donationService) {
        this.donationRepository = donationRepository;
        this.donationService = donationService;
    }

    /**
     * Hourly, matching CyberPay's own retry cadence — running more often would mostly re-ask
     * questions their webhook is about to answer anyway.
     *
     * <p>Deliberately not {@code @Transactional}. Each donation is settled in its own transaction
     * inside {@link DonationService#reconcilePending}, so one unreachable gateway call cannot roll
     * back the settlements that already succeeded in the same batch.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void sweepPendingDonations() {
        Instant now = Instant.now();
        List<Donation> due =
                donationRepository.findTop100ByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                        DonationStatus.PENDING, now.minus(GIVE_UP_AFTER), now.minus(SETTLE_GRACE));

        if (!due.isEmpty()) {
            log.info("Sweeping {} pending donation(s)", due.size());
            report(reconcile(due));
        }

        warnAboutAbandonedRows(now);
    }

    private Map<SettlementOutcome, Integer> reconcile(List<Donation> due) {
        Map<SettlementOutcome, Integer> counts = new EnumMap<>(SettlementOutcome.class);
        for (Donation donation : due) {
            SettlementOutcome outcome;
            try {
                outcome = donationService.reconcilePending(donation.getId());
            } catch (RuntimeException e) {
                // Almost always CyberPay being unreachable. Nothing to do but leave the row PENDING
                // and try again next hour; carrying on means one bad donation does not hide the
                // ninety-nine behind it.
                log.warn(
                        "Sweep could not reconcile donation id={}: {}",
                        donation.getId(),
                        e.toString());
                continue;
            }
            counts.merge(outcome, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * One line per run, and only for outcomes that actually occurred. {@code SETTLED} is the one to
     * watch: a non-zero count means the webhook path missed a payment that the donor had already
     * made, which is worth understanding rather than quietly recovering from forever.
     */
    private void report(Map<SettlementOutcome, Integer> counts) {
        int settled = counts.getOrDefault(SettlementOutcome.SETTLED, 0);
        if (settled > 0) {
            log.warn(
                    "Sweep settled {} donation(s) the webhook never confirmed — check why the "
                            + "notifications did not arrive",
                    settled);
        }
        log.info("Sweep finished: {}", counts);
    }

    private void warnAboutAbandonedRows(Instant now) {
        long stale =
                donationRepository.countByStatusAndCreatedAtBefore(
                        DonationStatus.PENDING, now.minus(GIVE_UP_AFTER));
        if (stale > 0) {
            // Deliberately a count and not a list: these need a person, and the point is to make
            // the number visible rather than to print the same rows every hour forever.
            log.warn(
                    "{} donation(s) have been PENDING for over {} days and are no longer swept",
                    stale,
                    GIVE_UP_AFTER.toDays());
        }
    }
}
