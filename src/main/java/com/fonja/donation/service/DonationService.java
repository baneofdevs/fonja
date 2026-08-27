package com.fonja.donation.service;

import com.fonja.common.exception.ApiException;
import com.fonja.donation.dto.CyberPayTransaction;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.PaymentMethod;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.ledger.service.LedgerService;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The donation module's entry point.
 *
 * <p>Only the confirmation half exists so far. Initiating a donation — creating the PENDING row and
 * calling CyberPay for a checkout URL — is still to be written; see the TODO at the bottom.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    private final DonationRepository donationRepository;
    private final CyberPayClient cyberPayClient;
    private final LedgerService ledgerService;

    public DonationService(
            DonationRepository donationRepository,
            CyberPayClient cyberPayClient,
            LedgerService ledgerService) {
        this.donationRepository = donationRepository;
        this.cyberPayClient = cyberPayClient;
        this.ledgerService = ledgerService;
    }

    /**
     * Settles a donation in response to a CyberPay notification whose signature has already been
     * verified.
     *
     * <p>The signature proves the message came from CyberPay. It does not prove the money arrived,
     * so this re-queries CyberPay before crediting anyone — the webhook is a doorbell, the re-query
     * is the bank statement. Technical spec §5.5.
     *
     * <p><strong>Called up to 72 times for one payment.</strong> CyberPay redelivers every hour for
     * 72 hours until it receives a 2xx, so every path here is idempotent: a second call for a
     * donation already marked SUCCESS re-asserts the ledger entries (themselves guarded by unique
     * indexes) and returns without a second re-query.
     *
     * <p>Returning normally means "stop retrying". Throwing means "ask again later" — reserved for
     * states that a later attempt could genuinely resolve.
     *
     * @throws ApiException gatewayUnavailable if CyberPay cannot be reached, or reports a
     *     transaction that has not reached a verdict yet
     */
    @Transactional
    public void confirmFromWebhook(String gatewayReference, String paymentChannel) {
        Donation donation =
                donationRepository.findByGatewayReference(gatewayReference).orElse(null);
        if (donation == null) {
            // Not ours, or a notification that arrived before the initiate call committed. Either
            // way a retry will not help us and we must not manufacture a donation from a message —
            // the campaign and donor are not in the payload. Accepted, logged, dropped.
            log.warn("CyberPay notification for unknown reference={}, ignoring", gatewayReference);
            return;
        }

        switch (donation.getStatus()) {
            case SUCCESS -> {
                // A duplicate delivery. Re-post rather than return: if a previous attempt marked
                // the
                // donation and then died before the ledger write, this is what repairs it.
                log.debug(
                        "Donation id={} already confirmed, re-asserting ledger", donation.getId());
                ledgerService.recordDonation(donation);
                return;
            }
            case FAILED -> {
                log.warn(
                        "CyberPay notification for donation id={} which is already FAILED, ignoring",
                        donation.getId());
                return;
            }
            case PENDING -> {
                /* the only case worth re-querying */
            }
        }

        CyberPayTransaction transaction = cyberPayClient.requery(gatewayReference);

        if (transaction.isPending()) {
            // CyberPay's notification outran their own read model, or the status is one this
            // version does not recognise. Both are resolved by asking again in an hour.
            throw ApiException.gatewayUnavailable(
                    "CyberPay reports "
                            + gatewayReference
                            + " as '"
                            + transaction.status()
                            + "', which is not a verdict yet");
        }

        if (transaction.isFailed()) {
            donation.markFailed();
            log.info(
                    "Donation id={} marked FAILED: CyberPay status '{}'",
                    donation.getId(),
                    transaction.status());
            return;
        }

        if (!amountsAgree(transaction.amount(), donation.getAmount())) {
            // Never credit an amount we did not expect, in either direction. The likeliest cause is
            // the kobo-versus-naira assumption in CyberPayClient being wrong, and the second
            // likeliest is a genuine discrepancy — both need a human, neither is fixed by retrying.
            // Left PENDING on purpose so it shows up as unsettled rather than silently closing.
            log.error(
                    "REFUSING to credit donation id={}: expected {} but CyberPay reports {} for "
                            + "reference={}. Left PENDING for manual review.",
                    donation.getId(),
                    donation.getAmount(),
                    transaction.amount(),
                    gatewayReference);
            return;
        }

        donation.markSuccessful(PaymentMethod.fromChannel(paymentChannel));
        ledgerService.recordDonation(donation);
        log.info(
                "Donation id={} confirmed for {} via {}",
                donation.getId(),
                donation.getAmount(),
                paymentChannel);
    }

    /** compareTo, not equals: {@code 500.00} and {@code 500.0} are the same amount of money. */
    private static boolean amountsAgree(BigDecimal reported, BigDecimal expected) {
        return reported != null && reported.compareTo(expected) == 0;
    }

    // TODO (donation module): initiate — create the PENDING row, POST /api/v1/payments with our
    // MerchantRef and WebhookUrl, store the returned transactionReference as gatewayReference, and
    // hand the donor the redirectUrl. Blocked on staging credentials.
}
