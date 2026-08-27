package com.fonja.donation.service;

import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.exception.ApiException;
import com.fonja.donation.dto.CyberPayCheckout;
import com.fonja.donation.dto.CyberPayTransaction;
import com.fonja.donation.dto.InitiateDonationRequest;
import com.fonja.donation.dto.InitiateDonationResponse;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.PaymentMethod;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.ledger.service.LedgerService;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The donation module's entry point: opening a payment, and settling it when CyberPay says so.
 *
 * <p>The two halves meet at {@code merchant_reference}. Nothing else ties a donor clicking "give"
 * to a notification arriving minutes later from a different machine.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    /**
     * 16 hex characters after the prefix — 64 bits, comfortably unguessable and short enough that
     * no gateway will truncate it.
     */
    private static final int REFERENCE_RANDOM_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DonationRepository donationRepository;
    private final CampaignService campaignService;
    private final UserService userService;
    private final CyberPayClient cyberPayClient;
    private final LedgerService ledgerService;
    private final TransactionTemplate transactionTemplate;

    public DonationService(
            DonationRepository donationRepository,
            CampaignService campaignService,
            UserService userService,
            CyberPayClient cyberPayClient,
            LedgerService ledgerService,
            TransactionTemplate transactionTemplate) {
        this.donationRepository = donationRepository;
        this.campaignService = campaignService;
        this.userService = userService;
        this.cyberPayClient = cyberPayClient;
        this.ledgerService = ledgerService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Opens a hosted checkout session for a donation and returns the URL to send the donor to.
     *
     * <p><strong>Deliberately not one transaction.</strong> The donation row is committed on its
     * own, before CyberPay is called, and CyberPay's reference is stored in a second transaction
     * afterwards. Wrapping all three in one would mean a call that reaches CyberPay and then fails
     * to commit leaves a live checkout page for a donation that does not exist — the donor pays,
     * the webhook arrives for an unknown reference, and the money is unattributable. Splitting them
     * means the worst case is a PENDING row with no gateway reference, which the webhook can still
     * resolve through {@code MerchantRef}.
     *
     * <p>The cost is that an abandoned checkout leaves a PENDING row behind. That is the right way
     * round: a stale row is a reporting nuisance, a lost payment is not.
     *
     * @throws ApiException notFound if the campaign does not exist, conflict if it is not accepting
     *     donations, gatewayUnavailable if CyberPay refuses or cannot be reached
     */
    public InitiateDonationResponse initiate(Long donorId, InitiateDonationRequest request) {
        Donation donation =
                transactionTemplate.execute(
                        status -> {
                            Campaign campaign =
                                    campaignService.requireCampaignAcceptingDonations(
                                            request.campaignId());
                            User donor = userService.requireById(donorId);

                            // Currency comes off the campaign, never the request: a donation is
                            // counted towards the campaign's goal, so a second currency in the
                            // total would make the progress figure meaningless.
                            return donationRepository.save(
                                    new Donation(
                                            campaign,
                                            donor,
                                            request.amount(),
                                            campaign.getCurrency(),
                                            newMerchantReference()));
                        });

        CyberPayCheckout checkout;
        try {
            checkout =
                    cyberPayClient.initiatePayment(
                            donation.getMerchantReference(),
                            donation.getAmount(),
                            donation.getCurrency(),
                            "Donation to " + donation.getCampaign().getTitle(),
                            donation.getDonor());
        } catch (RuntimeException e) {
            // CyberPay never issued a checkout page, so no donor can be looking at one. Closing the
            // row keeps the pending list meaning "waiting on a donor", not "waiting on nothing".
            transactionTemplate.executeWithoutResult(
                    status ->
                            donationRepository
                                    .findById(donation.getId())
                                    .ifPresent(Donation::markFailed));
            log.warn("Donation id={} failed at initiate: {}", donation.getId(), e.getMessage());
            throw e;
        }

        Donation opened =
                transactionTemplate.execute(
                        status -> {
                            Donation managed =
                                    donationRepository
                                            .findById(donation.getId())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "Donation "
                                                                            + donation.getId()
                                                                            + " vanished mid-initiate"));
                            managed.attachGatewayReference(checkout.transactionReference());
                            return managed;
                        });

        log.info(
                "Donation id={} opened: {} {} to campaign id={}, merchantRef={} gatewayRef={}",
                opened.getId(),
                opened.getCurrency(),
                opened.getAmount(),
                request.campaignId(),
                opened.getMerchantReference(),
                checkout.transactionReference());

        return InitiateDonationResponse.from(opened, checkout.redirectUrl());
    }

    /**
     * A reference CyberPay will echo back to us. Random rather than sequential: it travels through
     * the donor's browser, and a predictable one would let anyone enumerate Fonja's donations by
     * guessing.
     */
    private static String newMerchantReference() {
        byte[] bytes = new byte[REFERENCE_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return "FNJ" + HexFormat.of().formatHex(bytes);
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
    public void confirmFromWebhook(
            String gatewayReference, String merchantReference, String paymentChannel) {
        Donation donation = resolve(gatewayReference, merchantReference);
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

    // TODO (donation module): sweep PENDING donations older than a day. An abandoned checkout
    // leaves one behind, and CyberPay's own status for it is Abandoned — a periodic re-query would
    // close them without waiting for a notification that is never coming.

    /**
     * Finds the donation a notification belongs to, by CyberPay's reference first and ours second.
     *
     * <p>The fallback covers exactly one gap: the initiate call reached CyberPay, but the second
     * transaction that stores their reference never committed. The row is on disk with only our
     * reference, and this is what still settles the donor's payment. When it fires, it also repairs
     * the row — the missing reference is filled in, so every later delivery takes the fast path and
     * the idempotency guard on {@code gateway_reference} starts working again.
     */
    private Donation resolve(String gatewayReference, String merchantReference) {
        Donation donation =
                donationRepository.findByGatewayReference(gatewayReference).orElse(null);
        if (donation != null || merchantReference == null || merchantReference.isBlank()) {
            return donation;
        }

        Donation byMerchantRef =
                donationRepository.findByMerchantReference(merchantReference).orElse(null);
        if (byMerchantRef == null) {
            return null;
        }

        if (byMerchantRef.getGatewayReference() == null) {
            log.warn(
                    "Donation id={} had no gateway reference; recovered from MerchantRef and "
                            + "backfilled with {}",
                    byMerchantRef.getId(),
                    gatewayReference);
            byMerchantRef.attachGatewayReference(gatewayReference);
            return byMerchantRef;
        }

        // Same MerchantRef, different transaction. Not a gap in our records — either CyberPay has
        // re-used a reference or someone is replaying one, and neither is something to settle.
        log.error(
                "MerchantRef {} resolves to donation id={} which already holds gateway reference "
                        + "{}, but the notification quotes {}. Ignoring.",
                merchantReference,
                byMerchantRef.getId(),
                byMerchantRef.getGatewayReference(),
                gatewayReference);
        return null;
    }

    /** compareTo, not equals: {@code 500.00} and {@code 500.0} are the same amount of money. */
    private static boolean amountsAgree(BigDecimal reported, BigDecimal expected) {
        return reported != null && reported.compareTo(expected) == 0;
    }
}
