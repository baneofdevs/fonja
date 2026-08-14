package com.fonja.campaign.controller;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.response.CampaignResponse;
import com.fonja.campaign.service.CampaignService;
import com.fonja.common.exception.ApiException;
import com.fonja.request.CreateCampaignRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CampaignController.class)
@AutoConfigureMockMvc(addFilters = false)
class CampaignControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long CAMPAIGN_ID = 5L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampaignService campaignService;

    private CampaignResponse sampleResponse(Long id) {
        return CampaignResponse.builder()
                .id(id)
                .creatorId(USER_ID)
                .title("Help me pay for surgery")
                .story("Long story")
                .category(CampaignCategory.MEDICAL)
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
                "Help me pay for surgery", "Long story", CampaignCategory.MEDICAL, BeneficiaryType.SELF,
                "Nigeria", null, BigDecimal.valueOf(500000), "NGN", false, "cover.jpg");
    }

    @Test
    void createReturnsCreatedCampaign() throws Exception {
        when(campaignService.createCampaign(eq(USER_ID), any())).thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(post("/api/v1/campaigns")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.responseData.id").value(CAMPAIGN_ID));
    }

    @Test
    void createWithMissingRequiredFieldsFailsValidation() throws Exception {
        CreateCampaignRequest invalid = new CreateCampaignRequest(
                "", null, null, null, "", null, null, null, false, null);

        mockMvc.perform(post("/api/v1/campaigns")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("01"));
    }

    @Test
    void getByIdReturnsCampaign() throws Exception {
        when(campaignService.getCampaignById(USER_ID, CAMPAIGN_ID)).thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(get("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.id").value(CAMPAIGN_ID));
    }

    @Test
    void getByIdNotFoundReturns404() throws Exception {
        when(campaignService.getCampaignById(USER_ID, CAMPAIGN_ID))
                .thenThrow(ApiException.notFound("Campaign " + CAMPAIGN_ID + " not found"));

        mockMvc.perform(get("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.responseCode").value("02"));
    }

    @Test
    void myCampaignsReturnsPagedList() throws Exception {
        when(campaignService.getCampaignsForUser(eq(USER_ID), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse(CAMPAIGN_ID))));

        mockMvc.perform(get("/api/v1/campaigns/me")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.content[0].id").value(CAMPAIGN_ID));
    }

    @Test
    void launchReturnsSubmittedCampaign() throws Exception {
        when(campaignService.submitForReview(USER_ID, CAMPAIGN_ID)).thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(post("/api/v1/campaigns/{id}/launch", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMessage").value("Campaign submitted for review"));
    }

    @Test
    void launchWithoutVerificationReturnsVerificationRequired() throws Exception {
        when(campaignService.submitForReview(USER_ID, CAMPAIGN_ID))
                .thenThrow(ApiException.verificationRequired("Verify your identity to submit this campaign for review"));

        mockMvc.perform(post("/api/v1/campaigns/{id}/launch", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.responseCode").value("06"));
    }

    @Test
    void deleteCallsServiceAndReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/v1/campaigns/{id}", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMessage").value("Campaign deleted"));

        verify(campaignService).deleteCampaign(USER_ID, CAMPAIGN_ID);
    }

    @Test
    void approveCallsServiceWithApprovedTrueAndNoReason() throws Exception {
        when(campaignService.reviewCampaign(USER_ID, CAMPAIGN_ID, true, null)).thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(post("/api/v1/campaigns/admin/{id}/approve", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID)))))
                .andExpect(status().isOk());

        verify(campaignService).reviewCampaign(USER_ID, CAMPAIGN_ID, true, null);
    }

    @Test
    void rejectWithoutReasonFailsValidation() throws Exception {
        mockMvc.perform(post("/api/v1/campaigns/admin/{id}/reject", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectWithReasonCallsServiceWithApprovedFalse() throws Exception {
        when(campaignService.reviewCampaign(USER_ID, CAMPAIGN_ID, false, "Story doesn't match evidence"))
                .thenReturn(sampleResponse(CAMPAIGN_ID));

        mockMvc.perform(post("/api/v1/campaigns/admin/{id}/reject", CAMPAIGN_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(USER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Story doesn't match evidence\"}"))
                .andExpect(status().isOk());

        verify(campaignService).reviewCampaign(USER_ID, CAMPAIGN_ID, false, "Story doesn't match evidence");
    }
}
