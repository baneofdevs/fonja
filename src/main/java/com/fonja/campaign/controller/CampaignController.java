package com.fonja.campaign.controller;

import com.fonja.campaign.response.CampaignResponse;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.request.AddCampaignImageRequest;
import com.fonja.request.CreateCampaignRequest;
import com.fonja.request.RejectCampaignRequest;
import com.fonja.request.UpdateCampaignRequest;
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

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @PostMapping
    public CustomResponse<CampaignResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody CreateCampaignRequest request) {
        return CustomResponse.success("Campaign created",
                campaignService.createCampaign(JwtService.userIdFrom(jwt), request));
    }

    @GetMapping("/me")
    public CustomResponse<Page<CampaignResponse>> myCampaigns(@AuthenticationPrincipal Jwt jwt,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return CustomResponse.success(campaignService.getCampaignsForUser(JwtService.userIdFrom(jwt), pageable));
    }

    @GetMapping("/{campaignId}")
    public CustomResponse<CampaignResponse> getById(@AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        return CustomResponse.success(campaignService.getCampaignById(JwtService.userIdFrom(jwt), campaignId));
    }

    @PatchMapping("/{campaignId}")
    public CustomResponse<CampaignResponse> update(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable Long campaignId,
                                                    @Valid @RequestBody UpdateCampaignRequest request) {
        return CustomResponse.success("Campaign updated",
                campaignService.updateCampaign(JwtService.userIdFrom(jwt), campaignId, request));
    }

    @PostMapping("/{campaignId}/images")
    public CustomResponse<CampaignResponse> addImage(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable Long campaignId,
                                                      @Valid @RequestBody AddCampaignImageRequest request) {
        return CustomResponse.success("Image added",
                campaignService.addCampaignImage(JwtService.userIdFrom(jwt), campaignId, request.filePath()));
    }

    @DeleteMapping("/{campaignId}/images/{imageId}")
    public CustomResponse<CampaignResponse> removeImage(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable Long campaignId,
                                                         @PathVariable Long imageId) {
        return CustomResponse.success("Image removed",
                campaignService.removeCampaignImage(JwtService.userIdFrom(jwt), campaignId, imageId));
    }

    /** BRD's "Launch" action: DRAFT -> PENDING_REVIEW. */
    @PostMapping("/{campaignId}/launch")
    public CustomResponse<CampaignResponse> launch(@AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        return CustomResponse.success("Campaign submitted for review",
                campaignService.submitForReview(JwtService.userIdFrom(jwt), campaignId));
    }

    @DeleteMapping("/{campaignId}")
    public CustomResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        campaignService.deleteCampaign(JwtService.userIdFrom(jwt), campaignId);
        return CustomResponse.success("Campaign deleted", null);
    }

    @GetMapping("/admin/review-queue")
    public CustomResponse<Page<CampaignResponse>> reviewQueue(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return CustomResponse.success(campaignService.reviewQueue(pageable));
    }

    @PostMapping("/admin/{campaignId}/approve")
    public CustomResponse<CampaignResponse> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long campaignId) {
        return CustomResponse.success("Campaign approved",
                campaignService.reviewCampaign(JwtService.userIdFrom(jwt), campaignId, true, null));
    }

    @PostMapping("/admin/{campaignId}/reject")
    public CustomResponse<CampaignResponse> reject(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable Long campaignId,
                                                    @Valid @RequestBody RejectCampaignRequest request) {
        return CustomResponse.success("Campaign rejected",
                campaignService.reviewCampaign(JwtService.userIdFrom(jwt), campaignId, false, request.reason()));
    }
}
