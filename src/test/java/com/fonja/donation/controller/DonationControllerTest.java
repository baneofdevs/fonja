package com.fonja.donation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.common.config.SecurityConfig;
import com.fonja.common.exception.GlobalExceptionHandler;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import com.fonja.donation.dto.InitiateDonationRequest;
import com.fonja.donation.dto.InitiateDonationResponse;
import com.fonja.donation.service.DonationService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Runs against the real {@link SecurityConfig}. The rule worth proving is the inverse of the
 * webhook's: donating needs a token, where the webhook must work without one.
 */
@WebMvcTest(controllers = DonationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class DonationControllerTest {

    private static final Long DONOR_ID = 4L;
    private static final Long CAMPAIGN_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DonationService donationService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private RequestPostProcessor donor() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(DONOR_ID)));
    }

    private String body(String amount) {
        return "{\"campaignId\":" + CAMPAIGN_ID + ",\"amount\":" + amount + "}";
    }

    @Test
    void returnsTheCheckoutUrlToASignedInDonor() throws Exception {
        when(donationService.initiate(anyLong(), any()))
                .thenReturn(
                        new InitiateDonationResponse(
                                12L,
                                CAMPAIGN_ID,
                                new BigDecimal("5000.00"),
                                new BigDecimal("129.00"),
                                new BigDecimal("5129.00"),
                                "NGN",
                                "FNJ0123456789abcdef",
                                "https://payment.cyberpay.ng/pay?reference=KIN-1"));

        mockMvc.perform(
                        post("/api/v1/donations")
                                .with(donor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("5000.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(
                        jsonPath("$.responseData.checkoutUrl")
                                .value("https://payment.cyberpay.ng/pay?reference=KIN-1"))
                // The gateway fee reaches the client before the redirect, so the donor is told the
                // real total up front rather than meeting it on CyberPay's page. The donation
                // itself
                // is unchanged — the fee rides on top and Fonja is settled the full 5,000.
                .andExpect(jsonPath("$.responseData.amount").value(5000.00))
                .andExpect(jsonPath("$.responseData.processingFee").value(129.00))
                .andExpect(jsonPath("$.responseData.totalPayable").value(5129.00));

        // The donor id comes from the token's subject — there is no field in the request body that
        // could attribute a donation to someone else.
        verify(donationService).initiate(eqDonor(), any(InitiateDonationRequest.class));
    }

    @Test
    void donatingRequiresTheDonorToBeSignedIn() throws Exception {
        // Donating needs an account and nothing more — but it does need an account. Only raising
        // money is gated on verification.
        mockMvc.perform(
                        post("/api/v1/donations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("5000.00")))
                .andExpect(status().isUnauthorized());

        verify(donationService, never()).initiate(anyLong(), any());
    }

    @Test
    void rejectsADonationBelowTheMinimum() throws Exception {
        mockMvc.perform(
                        post("/api/v1/donations")
                                .with(donor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("50.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("01"));

        verify(donationService, never()).initiate(anyLong(), any());
    }

    @Test
    void rejectsAnAmountFinerThanKobo() throws Exception {
        // CyberPay is told the amount as an integer number of kobo, so a third decimal place has
        // nowhere to go — better refused here than silently rounded into a mismatch the webhook
        // would later refuse to credit.
        mockMvc.perform(
                        post("/api/v1/donations")
                                .with(donor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("5000.005")))
                .andExpect(status().isBadRequest());

        verify(donationService, never()).initiate(anyLong(), any());
    }

    @Test
    void rejectsARequestWithNoCampaign() throws Exception {
        mockMvc.perform(
                        post("/api/v1/donations")
                                .with(donor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":5000.00}"))
                .andExpect(status().isBadRequest());

        verify(donationService, never()).initiate(anyLong(), any());
    }

    private static Long eqDonor() {
        return org.mockito.ArgumentMatchers.eq(DONOR_ID);
    }
}
