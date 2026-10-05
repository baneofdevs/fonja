package com.fonja.campaign.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.campaign.dto.CampaignResponse;
import com.fonja.campaign.dto.CategoryRef;
import com.fonja.campaign.dto.CreateCampaignRequest;
import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.config.SecurityConfig;
import com.fonja.common.exception.ApiException;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs against the real {@link SecurityConfig} rather than with the filter chain switched off, so
 * the authorization rules guarding {@code /api/v1/campaigns/admin/**} are covered here too — those
 * rules live in SecurityConfig, and a slice that disables filters would assert nothing about them.
 */
@WebMvcTest(controllers = CampaignController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class CampaignControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final Long CAMPAIGN_ID = 5L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CampaignService campaignService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private RequestPostProcessor creator() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(USER_ID)));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(ADMIN_ID)))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private CampaignResponse sampleResponse(Long id) {
        return CampaignResponse.builder()
                .id(id)
                .creatorId(USER_ID)
                .title("Help me pay for surgery")
                .story("Long story")
                .category(new CategoryRef("medical-health", "Medical & Health"))
                .beneficiaryType(BeneficiaryType.SELF)
                .country("Nigeria")
                .goalAmount(BigDecimal.valueOf(500000))
                .currency("NGN")
                .images(List.of())
                .lifecycleState(CampaignLifecycleState.DRAFT)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private CreateCampaignRequest validCreateRequest() {
        return new CreateCampaignRequest(
                "Help me pay for surgery",
                "Long story",
                "medical-health",
                null,
                BeneficiaryType.SELF,
                "Nigeria",
                null,
                BigDecimal.valueOf(500000),
                "NGN",
                false,
                "cover.jpg",
                null);
    }

    @Test
    void createReturnsCreatedCampaign() throws Exception {
        when(campaignService.createCampaign(eq(USER_ID), any()))
                .thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(
                        post("/api/v1/campaigns")
                                .with(creator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.responseData.id").value(CAMPAIGN_ID));
    }

    @Test
    void createWithMissingRequiredFieldsFailsValidation() throws Exception {
        CreateCampaignRequest invalid =
                new CreateCampaignRequest(
                        "", null, null, null, null, "", null, null, null, false, null, null);

        mockMvc.perform(
                        post("/api/v1/campaigns")
                                .with(creator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("01"));
    }

    @Test
    void getByIdReturnsCampaign() throws Exception {
        when(campaignService.getCampaignById(USER_ID, CAMPAIGN_ID))
                .thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(get("/api/v1/campaigns/{id}", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.id").value(CAMPAIGN_ID));
    }

    @Test
    void getByIdNotFoundReturns404() throws Exception {
        when(campaignService.getCampaignById(USER_ID, CAMPAIGN_ID))
                .thenThrow(ApiException.notFound("Campaign " + CAMPAIGN_ID + " not found"));

        mockMvc.perform(get("/api/v1/campaigns/{id}", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.responseCode").value("02"));
    }

    @Test
    void myCampaignsReturnsPagedList() throws Exception {
        when(campaignService.getCampaignsForUser(eq(USER_ID), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse(CAMPAIGN_ID))));

        mockMvc.perform(get("/api/v1/campaigns/me").with(creator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.content[0].id").value(CAMPAIGN_ID));
    }

    @Test
    void launchReturnsSubmittedCampaign() throws Exception {
        when(campaignService.submitForReview(USER_ID, CAMPAIGN_ID))
                .thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(post("/api/v1/campaigns/{id}/launch", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMessage").value("Campaign submitted for review"));
    }

    @Test
    void launchWithoutVerificationReturnsVerificationRequired() throws Exception {
        when(campaignService.submitForReview(USER_ID, CAMPAIGN_ID))
                .thenThrow(
                        ApiException.verificationRequired(
                                "Submit your identity verification to launch this campaign"));

        mockMvc.perform(post("/api/v1/campaigns/{id}/launch", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.responseCode").value("06"));
    }

    @Test
    void rejectionReasonIsReturnedToTheCreator() throws Exception {
        CampaignResponse rejected =
                CampaignResponse.builder()
                        .id(CAMPAIGN_ID)
                        .creatorId(USER_ID)
                        .lifecycleState(CampaignLifecycleState.DRAFT)
                        .rejectionReason("Story doesn't match evidence")
                        .build();
        when(campaignService.getCampaignById(USER_ID, CAMPAIGN_ID)).thenReturn(rejected);

        mockMvc.perform(get("/api/v1/campaigns/{id}", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.responseData.rejectionReason")
                                .value("Story doesn't match evidence"));
    }

    @Test
    void deleteCallsServiceAndReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/v1/campaigns/{id}", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMessage").value("Campaign deleted"));

        verify(campaignService).deleteCampaign(USER_ID, CAMPAIGN_ID);
    }

    @Test
    void approveCallsServiceWithApprovedTrueAndNoReason() throws Exception {
        when(campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, true, null))
                .thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(post("/api/v1/campaigns/admin/{id}/approve", CAMPAIGN_ID).with(admin()))
                .andExpect(status().isOk());

        verify(campaignService).reviewCampaign(ADMIN_ID, CAMPAIGN_ID, true, null);
    }

    @Test
    void rejectWithoutReasonFailsValidation() throws Exception {
        mockMvc.perform(
                        post("/api/v1/campaigns/admin/{id}/reject", CAMPAIGN_ID)
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectWithReasonCallsServiceWithApprovedFalse() throws Exception {
        when(campaignService.reviewCampaign(
                        ADMIN_ID, CAMPAIGN_ID, false, "Story doesn't match evidence"))
                .thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(
                        post("/api/v1/campaigns/admin/{id}/reject", CAMPAIGN_ID)
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Story doesn't match evidence\"}"))
                .andExpect(status().isOk());

        verify(campaignService)
                .reviewCampaign(ADMIN_ID, CAMPAIGN_ID, false, "Story doesn't match evidence");
    }

    @Test
    void adminRouteIsForbiddenToNonAdmins() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/admin/{id}/approve", CAMPAIGN_ID).with(creator()))
                .andExpect(status().isForbidden());

        verify(campaignService, never()).reviewCampaign(any(), any(), anyBoolean(), any());
    }

    @Test
    void adminReviewQueueIsForbiddenToNonAdmins() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns/admin/review-queue").with(creator()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminReviewQueueIsAllowedForAdmins() throws Exception {
        when(campaignService.reviewQueue(any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse(CAMPAIGN_ID))));

        mockMvc.perform(get("/api/v1/campaigns/admin/review-queue").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.content[0].id").value(CAMPAIGN_ID));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns/me")).andExpect(status().isUnauthorized());
    }

    /** Guards against the page-size parameter being passed straight through to the repository. */
    @Test
    void pageSizeIsClampedToTheMaximum() throws Exception {
        when(campaignService.getCampaignsForUser(eq(USER_ID), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/campaigns/me").param("size", "5000").with(creator()))
                .andExpect(status().isOk());

        verify(campaignService)
                .getCampaignsForUser(
                        eq(USER_ID), argThat(pageable -> pageable.getPageSize() == 100));
    }
}
