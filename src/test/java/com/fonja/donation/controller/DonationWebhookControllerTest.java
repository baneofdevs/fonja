package com.fonja.donation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.common.config.SecurityConfig;
import com.fonja.common.exception.ApiException;
import com.fonja.common.exception.GlobalExceptionHandler;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import com.fonja.donation.service.CyberPaySignatureVerifier;
import com.fonja.donation.service.DonationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs against the real {@link SecurityConfig}, because the thing most worth proving here is that
 * the endpoint is reachable without a bearer token — and that being reachable is not the same as
 * being open.
 */
@WebMvcTest(controllers = DonationWebhookController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class DonationWebhookControllerTest {

    private static final String BODY =
            "{\"Code\":\"PY00\",\"Succeeded\":true,\"Data\":{\"Status\":\"Successful\","
                    + "\"Reference\":\"KIN190523841971254995\",\"MerchantRef\":\"FNJ0123456789abcdef\","
                    + "\"PaymentChannel\":\"BankTransfer\"}}";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CyberPaySignatureVerifier signatureVerifier;
    @MockitoBean private DonationService donationService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Test
    void acceptsASignedNotificationWithoutABearerToken() throws Exception {
        mockMvc.perform(
                        post("/api/v1/donations/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(CyberPaySignatureVerifier.SIGNATURE_HEADER, "deadbeef")
                                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"));

        // Both references are handed on: CyberPay's is the primary handle, ours is the fallback
        // for a donation whose gateway reference we never managed to store.
        verify(donationService)
                .confirmFromWebhook("KIN190523841971254995", "FNJ0123456789abcdef", "BankTransfer");
    }

    @Test
    void refusesToActOnAnUnsignedNotification() {
        // The endpoint is permitAll, so this rejection is the handler's own doing, not the filter
        // chain's. Without it anyone could POST a "payment successful" and credit a campaign.
        doThrow(ApiException.unauthorized("Invalid signature"))
                .when(signatureVerifier)
                .verify(any(), any());

        org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                                mockMvc.perform(
                                                post("/api/v1/donations/webhook")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(BODY))
                                        .andExpect(status().isUnauthorized()))
                .doesNotThrowAnyException();

        verifyNoInteractions(donationService);
    }

    @Test
    void signatureIsCheckedBeforeThePayloadIsEvenParsed() throws Exception {
        doThrow(ApiException.unauthorized("Invalid signature"))
                .when(signatureVerifier)
                .verify(any(), any());

        mockMvc.perform(
                        post("/api/v1/donations/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(CyberPaySignatureVerifier.SIGNATURE_HEADER, "deadbeef")
                                .content("this is not json"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(donationService);
    }

    @Test
    void rejectsASignedPayloadCarryingNoReference() throws Exception {
        // Nothing to re-query. 400 rather than 503: redelivering the same bytes cannot help.
        mockMvc.perform(
                        post("/api/v1/donations/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(CyberPaySignatureVerifier.SIGNATURE_HEADER, "deadbeef")
                                .content(
                                        "{\"Code\":\"PY00\",\"Data\":{\"Status\":\"Successful\"}}"))
                .andExpect(status().isBadRequest());

        verify(donationService, never()).confirmFromWebhook(anyString(), any(), any());
    }

    @Test
    void returns503WhenTheDonationServiceCannotReachAVerdict() throws Exception {
        // Any non-2xx makes CyberPay redeliver in an hour, which is the point.
        doThrow(ApiException.gatewayUnavailable("not settled yet"))
                .when(donationService)
                .confirmFromWebhook(anyString(), any(), any());

        mockMvc.perform(
                        post("/api/v1/donations/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(CyberPaySignatureVerifier.SIGNATURE_HEADER, "deadbeef")
                                .content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void readsPascalCaseAndCamelCaseAlike() throws Exception {
        // CyberPay sends PascalCase on the webhook and camelCase on the re-query response for the
        // same fields, so neither spelling can be assumed.
        mockMvc.perform(
                        post("/api/v1/donations/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(CyberPaySignatureVerifier.SIGNATURE_HEADER, "deadbeef")
                                .content(
                                        "{\"code\":\"PY00\",\"data\":{\"reference\":\"KIN-1\","
                                                + "\"paymentChannel\":\"Card\"}}"))
                .andExpect(status().isOk());

        verify(donationService).confirmFromWebhook("KIN-1", null, "Card");
    }
}
