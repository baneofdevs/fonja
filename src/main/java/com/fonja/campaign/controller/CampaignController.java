package com.fonja.campaign.controller;

import com.fonja.campaign.dto.AddCampaignImageRequest;
import com.fonja.campaign.dto.CampaignFeedSort;
import com.fonja.campaign.dto.CampaignResponse;
import com.fonja.campaign.dto.CreateCampaignRequest;
import com.fonja.campaign.dto.RejectCampaignRequest;
import com.fonja.campaign.dto.UpdateCampaignRequest;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.donation.dto.DonationResponse;
import com.fonja.donation.service.DonationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CampaignService campaignService;
    private final DonationService donationService;

    public CampaignController(CampaignService campaignService, DonationService donationService) {
        this.campaignService = campaignService;
        this.donationService = donationService;
    }

    @PostMapping
    public CustomResponse<CampaignResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCampaignRequest request) {
        return CustomResponse.success(
                "Campaign created",
                campaignService.createCampaign(JwtService.userIdFrom(jwt), request));
    }

    /**
     * The public feed. No token required — see {@code SecurityConfig}. Search is a plain substring
     * match on title and location; anything fancier belongs in a search index, not here.
     */
    @GetMapping
    public CustomResponse<Page<CampaignResponse>> feed(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "NEWEST") CampaignFeedSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(
                campaignService.publicFeed(category, q, sort, pageable(page, size)));
    }

    @GetMapping("/me")
    public CustomResponse<Page<CampaignResponse>> myCampaigns(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(
                campaignService.getCampaignsForUser(
                        JwtService.userIdFrom(jwt), pageable(page, size)));
    }

    /**
     * Public for campaigns that are live or have ended; the creator additionally sees their own
     * drafts. {@code jwt} is null for an anonymous visitor, which the service treats as "not the
     * owner".
     */
    @GetMapping("/{campaignId}")
    public CustomResponse<CampaignResponse> getById(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        return CustomResponse.success(
                campaignService.getCampaignById(JwtService.userIdFromOrNull(jwt), campaignId));
    }

    /** A campaign's supporters. Public for the same campaigns the page itself is public for. */
    @GetMapping("/{campaignId}/donations")
    public CustomResponse<Page<DonationResponse>> donations(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(
                donationService.campaignDonations(
                        JwtService.userIdFromOrNull(jwt), campaignId, pageable(page, size)));
    }

    @PatchMapping("/{campaignId}")
    public CustomResponse<CampaignResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @Valid @RequestBody UpdateCampaignRequest request) {
        return CustomResponse.success(
                "Campaign updated",
                campaignService.updateCampaign(JwtService.userIdFrom(jwt), campaignId, request));
    }

    @PostMapping("/{campaignId}/images")
    public CustomResponse<CampaignResponse> addImage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @Valid @RequestBody AddCampaignImageRequest request) {
        return CustomResponse.success(
                "Image added",
                campaignService.addCampaignImage(
                        JwtService.userIdFrom(jwt), campaignId, request.filePath()));
    }

    @DeleteMapping("/{campaignId}/images/{imageId}")
    public CustomResponse<CampaignResponse> removeImage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @PathVariable Long imageId) {
        return CustomResponse.success(
                "Image removed",
                campaignService.removeCampaignImage(
                        JwtService.userIdFrom(jwt), campaignId, imageId));
    }

    /** BRD's "Launch" action: DRAFT -> PENDING_REVIEW. */
    @PostMapping("/{campaignId}/launch")
    public CustomResponse<CampaignResponse> launch(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        return CustomResponse.success(
                "Campaign submitted for review",
                campaignService.submitForReview(JwtService.userIdFrom(jwt), campaignId));
    }

    @DeleteMapping("/{campaignId}")
    public CustomResponse<Void> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        campaignService.deleteCampaign(JwtService.userIdFrom(jwt), campaignId);
        return CustomResponse.success("Campaign deleted", null);
    }

    @GetMapping("/admin/review-queue")
    public CustomResponse<Page<CampaignResponse>> reviewQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(campaignService.reviewQueue(pageable(page, size)));
    }

    @PostMapping("/admin/{campaignId}/approve")
    public CustomResponse<CampaignResponse> approve(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        return CustomResponse.success(
                "Campaign approved",
                campaignService.reviewCampaign(JwtService.userIdFrom(jwt), campaignId, true, null));
    }

    @PostMapping("/admin/{campaignId}/reject")
    public CustomResponse<CampaignResponse> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @Valid @RequestBody RejectCampaignRequest request) {
        return CustomResponse.success(
                "Campaign rejected",
                campaignService.reviewCampaign(
                        JwtService.userIdFrom(jwt), campaignId, false, request.reason()));
    }

    // Clamps client paging into a sane range: no negative pages, no thousand-row pages.
    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
