package com.fonja.campaign.service;

import com.fonja.request.CreateCampaignRequest;
import com.fonja.request.UpdateCampaignRequest;
import com.fonja.campaign.response.CampaignResponse;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignImage;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.repository.CampaignImageRepository;
import com.fonja.campaign.repository.CampaignRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignImageRepository campaignImageRepository;
    private final UserService userService;

    public CampaignService(CampaignRepository campaignRepository, CampaignImageRepository campaignImageRepository,
                            UserService userService) {
        this.campaignRepository = campaignRepository;
        this.campaignImageRepository = campaignImageRepository;
        this.userService = userService;
    }

    // Creates a new draft campaign for the given user.
    @Transactional
    public CampaignResponse createCampaign(Long userId, CreateCampaignRequest request) {
        User creator = userService.requireById(userId);

        Campaign campaign = new Campaign(
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
                request.coverMediaPath()
        );

        Campaign saved = campaignRepository.save(campaign);
        log.info("Campaign id={} created by user id={}", saved.getId(), userId);
        return mapcampaignEntityToResponse(userId, saved);
    }

    // Lists a user's own campaigns, newest first. Cancelled (soft-deleted) campaigns are excluded.
    @Transactional(readOnly = true)
    public Page<CampaignResponse> getCampaignsForUser(Long userId, Pageable pageable) {
        return campaignRepository.findByCreatorIdAndLifecycleStateNotOrderByCreatedAtDesc(
                        userId, CampaignLifecycleState.CANCELLED, pageable)
                .map(campaign -> mapcampaignEntityToResponse(userId, campaign));
    }

    // Gets one campaign by id. Owner-only for now; a cancelled (soft-deleted) campaign reads as not-found.
    @Transactional(readOnly = true)
    public CampaignResponse getCampaignById(Long userId, Long campaignId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);
        if (campaign.getLifecycleState() == CampaignLifecycleState.CANCELLED) {
            throw ApiException.notFound("Campaign " + campaignId + " not found");
        }
        return mapcampaignEntityToResponse(userId, campaign);
    }

    // Partially updates a campaign. Editable at any stage except after it's ended.
    @Transactional
    public CampaignResponse updateCampaign(Long userId, Long campaignId, UpdateCampaignRequest request) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (hasEnded(campaign)) {
            throw ApiException.conflict("Cannot update a campaign that has already ended");
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

        log.info("Campaign id={} updated by user id={}", campaignId, userId);
        return mapcampaignEntityToResponse(userId, campaign);
    }

    // Adds an additional image to a campaign, appended after any existing ones.
    @Transactional
    public CampaignResponse addCampaignImage(Long userId, Long campaignId, String filePath) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (hasEnded(campaign)) {
            throw ApiException.conflict("Cannot add an image to a campaign that has already ended");
        }

        int nextSortOrder = campaignImageRepository.countByCampaignId(campaignId);
        campaignImageRepository.save(new CampaignImage(campaign, filePath, nextSortOrder));

        log.info("Image added to campaign id={} by user id={}", campaignId, userId);
        return mapcampaignEntityToResponse(userId, campaign);
    }

    // Removes an additional image from a campaign.
    @Transactional
    public CampaignResponse removeCampaignImage(Long userId, Long campaignId, Long imageId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (hasEnded(campaign)) {
            throw ApiException.conflict("Cannot remove an image from a campaign that has already ended");
        }

        CampaignImage image = campaignImageRepository.findById(imageId)
                .orElseThrow(() -> ApiException.notFound("Image " + imageId + " not found"));
        if (!image.getCampaign().getId().equals(campaignId)) {
            throw ApiException.notFound("Image " + imageId + " not found");
        }
        campaignImageRepository.delete(image);

        log.info("Image id={} removed from campaign id={} by user id={}", imageId, campaignId, userId);
        return mapcampaignEntityToResponse(userId, campaign);
    }

    // Creator submits a draft for admin review: DRAFT -> PENDING_REVIEW.
    @Transactional
    public CampaignResponse submitForReview(Long userId, Long campaignId) {
        Campaign campaign = findOwnedCampaignOrThrow(userId, campaignId);

        if (!campaign.getCreator().isVerified()) {
            throw ApiException.verificationRequired("Verify your identity to submit this campaign for review");
        }

        transition(campaign::submitForReview);
        log.info("Campaign id={} submitted for review by user id={}", campaignId, userId);
        return mapcampaignEntityToResponse(campaign.getCreator().getId(), campaign);
    }

    // Lists campaigns awaiting admin review, oldest submission first. Admin-only.
    @Transactional(readOnly = true)
    public Page<CampaignResponse> reviewQueue(Pageable pageable) {
        return campaignRepository.findByLifecycleStateOrderBySubmittedAtAsc(CampaignLifecycleState.PENDING_REVIEW, pageable)
                .map(campaign -> mapcampaignEntityToResponse(campaign.getCreator().getId(), campaign));
    }

    // Admin approves or rejects a pending campaign; an approval is auto-rejected if the campaign isn't actually ready.
    @Transactional
    public CampaignResponse reviewCampaign(Long adminUserId, Long campaignId, boolean approved, String rejectionReason) {
        Campaign campaign = findCampaignOrThrow(campaignId);

        if (approved) {
            String blocker = readinessBlocker(campaign);
            if (blocker != null) {
                transition(() -> campaign.reject(blocker));
                log.info("Campaign id={} auto-rejected on review by admin id={}: {}", campaignId, adminUserId, blocker);
            } else {
                transition(campaign::activate);
            }
        } else {
            transition(() -> campaign.reject(rejectionReason));
        }

        log.info("Campaign id={} reviewed by admin id={} approved={}", campaignId, adminUserId, approved);
        return mapcampaignEntityToResponse(campaign.getCreator().getId(), campaign);
    }

    // Hard-deletes a draft; soft-deletes (cancels) anything already submitted or live.
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

    // Maps a campaign entity to its response DTO.
    public CampaignResponse mapcampaignEntityToResponse(Long userId, Campaign campaign) {
        List<String> images = campaignImageRepository.findByCampaignIdOrderBySortOrderAsc(campaign.getId()).stream()
                .map(CampaignImage::getFilePath)
                .toList();

        return CampaignResponse.builder()
                .id(campaign.getId())
                .creatorId(userId)
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
                .createdAt(campaign.getCreatedAt())
                .updatedAt(campaign.getUpdatedAt())
                .submittedAt(campaign.getSubmittedAt())
                .approvedAt(campaign.getApprovedAt())
                .build();
    }

    // True once a campaign is in a terminal state — nothing left to edit or delete.
    private boolean hasEnded(Campaign campaign) {
        CampaignLifecycleState state = campaign.getLifecycleState();
        return state == CampaignLifecycleState.COMPLETED
                || state == CampaignLifecycleState.CANCELLED
                || state == CampaignLifecycleState.EXPIRED;
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
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiException.notFound("Campaign " + campaignId + " not found"));
    }

    // Finds a campaign by id and checks the given user owns it, or throws not-found.
    private Campaign findOwnedCampaignOrThrow(Long userId, Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        if (!campaign.getCreator().getId().equals(userId)) {
            // Same response as a missing campaign — confirming someone else's campaign exists is itself a leak.
            throw ApiException.notFound("Campaign " + campaignId + " not found");
        }
        return campaign;
    }

    // Runs a lifecycle transition, converting an illegal-state failure into a conflict response.
    private void transition(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }
}
