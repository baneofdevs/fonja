package com.fonja.donation.service;

import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.exception.ApiException;
import com.fonja.donation.dto.CyberPayCheckout;
import com.fonja.donation.dto.CyberPayTransaction;
import com.fonja.donation.dto.DonationResponse;
import com.fonja.donation.dto.DonationSummaryResponse;
import com.fonja.donation.dto.DonationTotals;
import com.fonja.donation.dto.InitiateDonationRequest;
import com.fonja.donation.dto.InitiateDonationResponse;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.entity.PaymentMethod;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.ledger.service.LedgerService;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final ApplicationEventPublisher events;

    public DonationService(
            DonationRepository donationRepository,
            CampaignService campaignService,
            UserService userService,
            CyberPayClient cyberPayClient,
            LedgerService ledgerService,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher events) {
        this.donationRepository = donationRepository;
        this.campaignService = campaignService;
        this.userService = userService;
        this.cyberPayClient = cyberPayClient;
        this.ledgerService = ledgerService;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
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
            // row keeps the pending list meaning "waiting on a donor", not "waiting on nothing" —
            // and keeps the sweep from re-querying a reference that was never created.
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

        return InitiateDonationResponse.from(opened, checkout.redirectUrl(), checkout.charge());
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

        if (settle(donation, gatewayReference, paymentChannel) == SettlementOutcome.UNDECIDED) {
            // CyberPay's notification outran their own read model, or the status is one this
            // version does not recognise. Both are resolved by asking again in an hour, and a 503
            // is how we ask.
            throw ApiException.gatewayUnavailable(
                    "CyberPay has not reached a verdict on " + gatewayReference + " yet");
        }
    }

    // --- Reads ------------------------------------------------------------------------

    /** Everything this user has given, any status, newest first. */
    @Transactional(readOnly = true)
    public Page<DonationResponse> myDonations(Long donorId, Pageable pageable) {
        return donationRepository
                .findByDonorIdOrderByCreatedAtDesc(donorId, pageable)
                .map(DonationResponse::forDonor);
    }

    /** Settled donations to every campaign this user created, newest first. */
    @Transactional(readOnly = true)
    public Page<DonationResponse> receivedDonations(Long creatorId, Pageable pageable) {
        return donationRepository
                .findByCampaignCreatorIdAndStatusOrderByCreatedAtDesc(
                        creatorId, DonationStatus.SUCCESS, pageable)
                .map(DonationResponse::withDonor);
    }

    /**
     * A campaign's supporters, for its public page. Gated on the campaign's own visibility so a
     * draft's donor list is exactly as hidden as the draft.
     */
    @Transactional(readOnly = true)
    public Page<DonationResponse> campaignDonations(
            Long viewerId, Long campaignId, Pageable pageable) {
        campaignService.requireVisibleCampaign(viewerId, campaignId);
        return donationRepository
                .findByCampaignIdAndStatusOrderByCreatedAtDesc(
                        campaignId, DonationStatus.SUCCESS, pageable)
                .map(DonationResponse::withDonor);
    }

    @Transactional(readOnly = true)
    public DonationSummaryResponse summary(Long userId) {
        DonationTotals given = donationRepository.totalsGivenBy(userId, DonationStatus.SUCCESS);
        DonationTotals received =
                donationRepository.totalsReceivedBy(userId, DonationStatus.SUCCESS);
        long donors = donationRepository.countDistinctDonorsFor(userId, DonationStatus.SUCCESS);
        return new DonationSummaryResponse(
                given.amount(), given.count(), received.amount(), received.count(), donors);
    }

    /** One of the donor's own donations. Someone else's reads as not found. */
    @Transactional(readOnly = true)
    public DonationResponse getForDonor(Long donorId, Long donationId) {
        return DonationResponse.forDonor(requireOwned(donorId, donationId));
    }

    /**
     * The donor is back from checkout and wants to know if it worked. If the webhook has already
     * landed this is just a read; if not, ask CyberPay now rather than making them wait for the
     * hourly sweep. Either way the answer is whatever the donation's status is afterwards — a
     * gateway that is unreachable leaves it PENDING, and the page says so.
     */
    @Transactional
    public DonationResponse confirm(Long donorId, Long donationId) {
        Donation donation = requireOwned(donorId, donationId);
        if (donation.getStatus() == DonationStatus.PENDING) {
            try {
                reconcilePending(donationId);
            } catch (ApiException e) {
                log.info(
                        "Donation id={} could not be confirmed on return: {}",
                        donationId,
                        e.getMessage());
            }
        }
        return DonationResponse.forDonor(donationRepository.findById(donationId).orElseThrow());
    }

    private Donation requireOwned(Long donorId, Long donationId) {
        Donation donation =
                donationRepository
                        .findById(donationId)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "Donation " + donationId + " not found"));
        if (!donation.getDonor().getId().equals(donorId)) {
            throw ApiException.notFound("Donation " + donationId + " not found");
        }
        return donation;
    }

    /**
     * Re-queries one donation that has been PENDING too long, and settles it if CyberPay has since
     * decided. The safety net under the webhook: a notification lost to a restart, an expired
     * tunnel, or a bug is money a creator is owed and would otherwise never see.
     *
     * <p>Its own transaction per donation, called from {@link PendingDonationSweepJob} one at a
     * time, so a single unreachable gateway or bad row cannot roll back a whole batch.
     *
     * @return what happened, for the sweep's summary line
     */
    @Transactional
    public SettlementOutcome reconcilePending(Long donationId) {
        Donation donation = donationRepository.findById(donationId).orElse(null);
        if (donation == null || donation.getStatus() != DonationStatus.PENDING) {
            // A webhook settled it between the sweep's query and this call. The webhook getting
            // there first is the normal case, not an error.
            return SettlementOutcome.ALREADY_SETTLED;
        }

        if (donation.getGatewayReference() == null) {
            // Initiate reached CyberPay but the transaction storing their reference never
            // committed, so there is no reference to re-query with — CyberPay's API is keyed on
            // theirs, not on our MerchantRef. Only an inbound webhook can resolve this one, and it
            // still can: confirmFromWebhook falls back to MerchantRef. Left alone, never failed.
            log.warn(
                    "Donation id={} has been PENDING since {} with no gateway reference; only a "
                            + "webhook quoting merchantRef={} can settle it",
                    donation.getId(),
                    donation.getCreatedAt(),
                    donation.getMerchantReference());
            return SettlementOutcome.UNRESOLVABLE;
        }

        return settle(donation, donation.getGatewayReference(), null);
    }

    /**
     * The single place a donation is allowed to change status, shared by the webhook and the sweep.
     *
     * <p>Shared on purpose rather than duplicated: the two paths differ only in what wakes them up,
     * and a rule that lived in one of them — the amount check above all — would eventually be the
     * rule the other one forgot.
     *
     * <p>Never marks a donation FAILED on age or silence, only on CyberPay explicitly saying so. A
     * donation wrongly closed here is unrecoverable: {@link #confirmFromWebhook} ignores a
     * notification for an already-FAILED donation, so a real payment arriving afterwards would be
     * dropped.
     *
     * @param paymentChannel the channel from a webhook, or null on the sweep path — where the
     *     re-query's own channel is used instead
     */
    private SettlementOutcome settle(
            Donation donation, String gatewayReference, String paymentChannel) {
        switch (donation.getStatus()) {
            case SUCCESS -> {
                // A duplicate delivery. Re-post rather than return: if a previous attempt marked
                // the donation and then died before the ledger write, this is what repairs it.
                log.debug(
                        "Donation id={} already confirmed, re-asserting ledger", donation.getId());
                ledgerService.recordDonation(donation);
                return SettlementOutcome.ALREADY_SETTLED;
            }
            case FAILED -> {
                log.warn(
                        "CyberPay notification for donation id={} which is already FAILED, ignoring",
                        donation.getId());
                return SettlementOutcome.ALREADY_SETTLED;
            }
            case PENDING -> {
                /* the only case worth re-querying */
            }
        }

        CyberPayTransaction transaction = cyberPayClient.requery(gatewayReference);

        if (transaction.isPending()) {
            log.debug(
                    "Donation id={} still undecided: CyberPay status '{}'",
                    donation.getId(),
                    transaction.status());
            return SettlementOutcome.UNDECIDED;
        }

        if (transaction.isFailed()) {
            donation.markFailed();
            log.info(
                    "Donation id={} marked FAILED: CyberPay status '{}'",
                    donation.getId(),
                    transaction.status());
            return SettlementOutcome.FAILED;
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
            return SettlementOutcome.AMOUNT_MISMATCH;
        }

        String channel = paymentChannel != null ? paymentChannel : transaction.paymentChannel();
        donation.markSuccessful(PaymentMethod.fromChannel(channel));
        ledgerService.recordDonation(donation);
        log.info(
                "Donation id={} confirmed for {} via {}",
                donation.getId(),
                donation.getAmount(),
                channel);

        // Only on the transition, never on the duplicate-delivery path above: a webhook that is
        // redelivered hourly for 72 hours must not mean 72 receipts.
        notifySettled(donation);
        campaignService.completeIfGoalReached(donation.getCampaign().getId());
        return SettlementOutcome.SETTLED;
    }

    /**
     * What a settlement attempt did. Exists because the webhook and the sweep must react
     * differently to the same result: "no verdict yet" is a 503 to CyberPay, asking them to
     * redeliver, but to the sweep it is simply a row to look at again next hour.
     */
    public enum SettlementOutcome {
        /** Confirmed and posted to the ledger. */
        SETTLED,
        /** CyberPay says this payment will never complete. */
        FAILED,
        /** No verdict yet, or a status this version does not recognise. Ask again later. */
        UNDECIDED,
        /** CyberPay's amount is not the one we recorded. Left PENDING for a human. */
        AMOUNT_MISMATCH,
        /** Already SUCCESS or FAILED before we looked. */
        ALREADY_SETTLED,
        /** PENDING with no gateway reference — nothing to re-query with. */
        UNRESOLVABLE
    }

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
    // Two emails per settlement: the donor's receipt and the creator's heads-up. Both are read off
    // the entity graph here, inside the transaction, because the listener runs outside it.
    private void notifySettled(Donation donation) {
        Campaign campaign = donation.getCampaign();
        events.publishEvent(
                new NotificationEvent.DonationReceipt(
                        Recipient.of(donation.getDonor()),
                        campaign.getId(),
                        campaign.getTitle(),
                        donation.getAmount(),
                        donation.getCurrency()));
        events.publishEvent(
                new NotificationEvent.DonationReceived(
                        Recipient.of(campaign.getCreator()),
                        campaign.getId(),
                        campaign.getTitle(),
                        donation.getDonor().getFullName(),
                        donation.getAmount(),
                        donation.getCurrency()));
    }

    private static boolean amountsAgree(BigDecimal reported, BigDecimal expected) {
        return reported != null && reported.compareTo(expected) == 0;
    }
}
