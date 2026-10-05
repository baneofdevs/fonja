package com.fonja.campaign.service;

import com.fonja.campaign.dto.CampaignResponse;
import com.fonja.campaign.dto.CreateCampaignRequest;
import com.fonja.campaign.dto.UpdateCampaignRequest;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignImage;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.entity.IllegalCampaignStateException;
import com.fonja.campaign.repository.CampaignImageRepository;
import com.fonja.campaign.repository.CampaignRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import com.fonja.verification.entity.VerificationStatus;
import com.fonja.verification.repository.VerificationRequestRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignImageRepository campaignImageRepository;
    private final VerificationRequestRepository verificationRequestRepository;
    private final UserService userService;
    private final ApplicationEventPublisher events;

    public CampaignService(
            CampaignRepository campaignRepository,
            CampaignImageRepository campaignImageRepository,
            VerificationRequestRepository verificationRequestRepository,
            UserService userService,
            ApplicationEventPublisher events) {
        this.campaignRepository = campaignRepository;
        this.campaignImageRepository = campaignImageRepository;
        this.verificationRequestRepository = verificationRequestRepository;
        this.userService = userService;
        this.events = events;
    }

    // Creates a new draft campaign for the given user.
    @Transactional
    public CampaignResponse createCampaign(Long userId, CreateCampaignRequest request) {
        User creator = userService.requireById(userId);

        Campaign campaign =
                new Campaign(
                        creator,
                        request.title(),
                        request.story(),
                        request.category(),
                        request.beneficiaryType(),
                        request.country(),
                        request.location(),
                        request.goalAmount(),
                        request.currency(),
                        request.autoAdjustGoal(),
                        request.coverMediaPath(),
                        request.deadline());

        Campaign saved = campaignRepository.save(campaign);
        log.info("Campaign id={} created by user id={}", saved.getId(), userId);
        return toResponse(saved);
    }

    // Lists a user's own campaigns, newest first. Cancelled (soft-deleted) campaigns are excluded.
    @Transactional(readOnly = true)
    public Page<CampaignResponse> getCampaignsForUser(Long userId, Pageable pageable) {
        return toResponsePage(
                campaignRepository.findByCreatorIdAndLifecycleStateNotOrderByCreatedAtDesc(
                        userId, CampaignLifecycleState.CANCELLED, pageable));
    }

    // Gets one campaign by id. Owner-only for now; a cancelled (soft-deleted) campaign reads as
    // not-found.
    @Transactional(readOnly = true)
    public CampaignResponse getCampaignById(Long userId, Long campaignId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);
        if (campaign.getLifecycleState() == CampaignLifecycleState.CANCELLED) {
            throw ApiException.notFound("Campaign " + campaignId + " not found");
        }
        return toResponse(campaign);
    }

    /**
     * Resolves a campaign that is currently able to accept money, for the donation module.
     *
     * <p>Not owner-scoped, unlike every other read here — a donor is by definition not the creator.
     * The guard is the lifecycle instead: only an ACTIVE campaign has been approved by an admin and
     * has a verified creator behind it, so this is the single place that decides a campaign may be
     * paid into.
     *
     * <p>Returns the entity rather than a DTO because the caller needs the creator to credit, and
     * the currency to record the donation in.
     *
     * @throws ApiException notFound if no such campaign exists, conflict if it exists but is not
     *     taking donations
     */
    @Transactional(readOnly = true)
    public Campaign requireCampaignAcceptingDonations(Long campaignId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "Campaign " + campaignId + " not found"));

        if (campaign.getLifecycleState() != CampaignLifecycleState.ACTIVE) {
            // Deliberately names the state. Unlike the owner-scoped reads, there is nothing to hide
            // here: the campaign is public once active, and a donor who has just been refused
            // deserves to know whether it ended, was cancelled, or was never approved.
            throw ApiException.conflict(
                    "Campaign "
                            + campaignId
                            + " is "
                            + campaign.getLifecycleState()
                            + " and is not accepting donations");
        }

        Instant deadline = campaign.getDeadline();
        if (deadline != null && deadline.isBefore(Instant.now())) {
            // The hourly expiry sweep has not caught up yet. Refusing on the deadline rather than
            // on the state closes the window between the two.
            throw ApiException.conflict("Campaign " + campaignId + " has passed its deadline");
        }

        return campaign;
    }

    /**
     * Partially updates a campaign.
     *
     * <p>Content fields stay editable until the campaign ends. The fundraising terms — goal,
     * currency and deadline — are frozen once it leaves DRAFT: donations are recorded against a
     * campaign's currency and counted towards its goal, so moving either after money starts
     * arriving would silently rewrite what donors gave to.
     */
    @Transactional
    public CampaignResponse updateCampaign(
            Long userId, Long campaignId, UpdateCampaignRequest request) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (campaign.hasEnded()) {
            throw ApiException.conflict("Cannot update a campaign that has already ended");
        }

        boolean isDraft = campaign.getLifecycleState() == CampaignLifecycleState.DRAFT;
        if (!isDraft
                && (request.goalAmount() != null
                        || request.currency() != null
                        || request.deadline() != null)) {
            throw ApiException.conflict(
                    "Goal, currency and deadline can only be changed while the campaign is a draft");
        }

        if (request.title() != null) {
            campaign.setTitle(request.title());
        }
        if (request.story() != null) {
            campaign.setStory(request.story());
        }
        if (request.category() != null) {
            campaign.setCategory(request.category());
        }
        if (request.beneficiaryType() != null) {
            campaign.setBeneficiaryType(request.beneficiaryType());
        }
        if (request.country() != null) {
            campaign.setCountry(request.country());
        }
        if (request.location() != null) {
            campaign.setLocation(request.location());
        }
        if (request.goalAmount() != null) {
            campaign.setGoalAmount(request.goalAmount());
        }
        if (request.currency() != null) {
            campaign.setCurrency(request.currency());
        }
        if (request.autoAdjustGoal() != null) {
            campaign.setAutoAdjustGoal(request.autoAdjustGoal());
        }
        if (request.coverMediaPath() != null) {
            campaign.setCoverMediaPath(request.coverMediaPath());
        }
        if (request.deadline() != null) {
            campaign.setDeadline(request.deadline());
        }

        log.info("Campaign id={} updated by user id={}", campaignId, userId);
        return toResponse(campaign);
    }

    // Adds an additional image to a campaign, appended after any existing ones.
    @Transactional
    public CampaignResponse addCampaignImage(Long userId, Long campaignId, String filePath) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (campaign.hasEnded()) {
            throw ApiException.conflict("Cannot add an image to a campaign that has already ended");
        }

        int nextSortOrder = campaignImageRepository.findMaxSortOrder(campaignId) + 1;
        campaignImageRepository.save(new CampaignImage(campaign, filePath, nextSortOrder));

        log.info("Image added to campaign id={} by user id={}", campaignId, userId);
        return toResponse(campaign);
    }

    // Removes an additional image from a campaign.
    @Transactional
    public CampaignResponse removeCampaignImage(Long userId, Long campaignId, Long imageId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (campaign.hasEnded()) {
            throw ApiException.conflict(
                    "Cannot remove an image from a campaign that has already ended");
        }

        CampaignImage image =
                campaignImageRepository
                        .findById(imageId)
                        .orElseThrow(
                                () -> ApiException.notFound("Image " + imageId + " not found"));
        if (!image.getCampaign().getId().equals(campaignId)) {
            throw ApiException.notFound("Image " + imageId + " not found");
        }
        campaignImageRepository.delete(image);

        log.info(
                "Image id={} removed from campaign id={} by user id={}",
                imageId,
                campaignId,
                userId);
        return toResponse(campaign);
    }

    /**
     * Creator submits a draft for admin review: DRAFT -> PENDING_REVIEW.
     *
     * <p>The Launch gate (technical spec §4.2, §4.3): an already-verified creator passes straight
     * through. An unverified creator passes as soon as they have a verification request in flight —
     * the campaign then queues for review while an admin works through their documents. Approval is
     * still gated on {@code verified}, which {@link #readinessBlocker} enforces at review time, so
     * nothing can go live on the strength of an unapproved submission.
     */
    @Transactional
    public CampaignResponse submitForReview(Long userId, Long campaignId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (!campaign.getCreator().isVerified() && !hasVerificationInFlight(userId)) {
            throw ApiException.verificationRequired(
                    "Submit your identity verification to launch this campaign");
        }

        transition(campaign::submitForReview);
        log.info("Campaign id={} submitted for review by user id={}", campaignId, userId);
        return toResponse(campaign);
    }

    // Lists campaigns awaiting admin review, oldest submission first. Admin-only.
    @Transactional(readOnly = true)
    public Page<CampaignResponse> reviewQueue(Pageable pageable) {
        return toResponsePage(
                campaignRepository.findByLifecycleStateOrderBySubmittedAtAsc(
                        CampaignLifecycleState.PENDING_REVIEW, pageable));
    }

    // Admin approves or rejects a pending campaign; an approval is auto-rejected if the campaign
    // isn't actually ready.
    @Transactional
    public CampaignResponse reviewCampaign(
            Long adminUserId, Long campaignId, boolean approved, String rejectionReason) {
        Campaign campaign = findCampaignOrThrow(campaignId);

        if (approved) {
            String blocker = readinessBlocker(campaign);
            if (blocker != null) {
                transition(() -> campaign.reject(blocker));
                log.info(
                        "Campaign id={} auto-rejected on review by admin id={}: {}",
                        campaignId,
                        adminUserId,
                        blocker);
                notifyReviewed(campaign, false, blocker);
            } else {
                transition(campaign::activate);
                notifyReviewed(campaign, true, null);
            }
        } else {
            transition(() -> campaign.reject(rejectionReason));
            notifyReviewed(campaign, false, rejectionReason);
        }

        log.info(
                "Campaign id={} reviewed by admin id={} approved={}",
                campaignId,
                adminUserId,
                approved);
        return toResponse(campaign);
    }

    /**
     * Hard-deletes a draft; soft-deletes (cancels) anything already submitted or live.
     *
     * <p>TODO (Day 3, with the donation module): cancelling a campaign that has already taken
     * donations needs a refund decision before it can be allowed. Today no donation can exist —
     * {@code DonationService} is still a stub — so there is nothing to strand.
     */
    @Transactional
    public void deleteCampaign(Long userId, Long campaignId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (campaign.getLifecycleState() == CampaignLifecycleState.DRAFT) {
            campaignRepository.delete(campaign);
            log.info("Campaign id={} hard-deleted by user id={}", campaignId, userId);
            return;
        }

        transition(campaign::cancel);
        log.info("Campaign id={} soft-deleted (cancelled) by user id={}", campaignId, userId);
    }

    private void notifyReviewed(Campaign campaign, boolean approved, String reason) {
        events.publishEvent(
                new NotificationEvent.CampaignReviewed(
                        Recipient.of(campaign.getCreator()),
                        campaign.getId(),
                        campaign.getTitle(),
                        approved,
                        reason));
    }

    /** True when the creator has a verification request awaiting an admin decision. */
    private boolean hasVerificationInFlight(Long userId) {
        return verificationRequestRepository
                .findByUserIdAndStatus(userId, VerificationStatus.PENDING)
                .isPresent();
    }

    // Maps a campaign entity to its response DTO. creatorId always comes off the entity: deriving
    // it
    // from the calling user would report the viewer as the creator on any non-owner read path.
    private CampaignResponse toResponse(Campaign campaign) {
        List<String> images =
                campaignImageRepository
                        .findByCampaignIdOrderBySortOrderAsc(campaign.getId())
                        .stream()
                        .map(CampaignImage::getFilePath)
                        .toList();
        return toResponse(campaign, images);
    }

    // Page variant: one image query for the whole page rather than one per campaign.
    private Page<CampaignResponse> toResponsePage(Page<Campaign> campaigns) {
        List<Long> ids = campaigns.map(Campaign::getId).getContent();
        if (ids.isEmpty()) {
            return campaigns.map(campaign -> toResponse(campaign, List.of()));
        }

        Map<Long, List<String>> imagesByCampaign = new LinkedHashMap<>();
        for (CampaignImage image :
                campaignImageRepository.findByCampaignIdInOrderByCampaignIdAscSortOrderAsc(ids)) {
            imagesByCampaign
                    .computeIfAbsent(image.getCampaign().getId(), key -> new ArrayList<>())
                    .add(image.getFilePath());
        }

        return campaigns.map(
                campaign ->
                        toResponse(
                                campaign,
                                imagesByCampaign.getOrDefault(campaign.getId(), List.of())));
    }

    private CampaignResponse toResponse(Campaign campaign, List<String> images) {
        return CampaignResponse.builder()
                .id(campaign.getId())
                .creatorId(campaign.getCreator().getId())
                .title(campaign.getTitle())
                .story(campaign.getStory())
                .category(campaign.getCategory())
                .beneficiaryType(campaign.getBeneficiaryType())
                .country(campaign.getCountry())
                .location(campaign.getLocation())
                .goalAmount(campaign.getGoalAmount())
                .currency(campaign.getCurrency())
                .coverMediaPath(campaign.getCoverMediaPath())
                .images(images)
                .lifecycleState(campaign.getLifecycleState())
                .rejectionReason(campaign.getRejectionReason())
                .deadline(campaign.getDeadline())
                .createdAt(campaign.getCreatedAt())
                .updatedAt(campaign.getUpdatedAt())
                .submittedAt(campaign.getSubmittedAt())
                .approvedAt(campaign.getApprovedAt())
                .build();
    }

    // Returns why a campaign isn't ready to go live, or null if it is.
    private String readinessBlocker(Campaign campaign) {
        if (!campaign.getCreator().isVerified()) {
            return "Creator is not a verified creator";
        }
        if (campaign.getStory() == null || campaign.getStory().isBlank()) {
            return "Campaign story is required before it can go live";
        }
        if (campaign.getCoverMediaPath() == null || campaign.getCoverMediaPath().isBlank()) {
            return "A cover image is required before it can go live";
        }
        return null;
    }

    // Finds a campaign by id, or throws not-found.
    private Campaign findCampaignOrThrow(Long campaignId) {
        return campaignRepository
                .findById(campaignId)
                .orElseThrow(() -> ApiException.notFound("Campaign " + campaignId + " not found"));
    }

    // Finds a campaign by id and checks the given user owns it, or throws not-found.
    private Campaign findOwnedCampaignOrThrow(Long userId, Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        if (!campaign.getCreator().getId().equals(userId)) {
            // Same response as a missing campaign — confirming someone else's campaign exists is
            // itself a leak.
            throw ApiException.notFound("Campaign " + campaignId + " not found");
        }
        return campaign;
    }

    // Runs a lifecycle transition, converting an illegal-transition failure into a conflict
    // response. Deliberately narrow: an unrelated IllegalStateException from inside the transition
    // is a bug and should surface as a 500, not a 409.
    private void transition(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalCampaignStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }
}
