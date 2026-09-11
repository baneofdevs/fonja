package com.fonja.payout.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.common.config.SecurityConfig;
import com.fonja.common.exception.GlobalExceptionHandler;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import com.fonja.payout.dto.BankAccountResponse;
import com.fonja.payout.dto.PayoutQuoteResponse;
import com.fonja.payout.dto.PayoutResponse;
import com.fonja.payout.dto.RequestPayoutRequest;
import com.fonja.payout.entity.PayoutStatus;
import com.fonja.payout.service.PayoutService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Runs against the real {@link SecurityConfig}, because the rules worth proving here are its rules:
 * that a creator cannot reach the admin settlement routes, and that nothing on this controller can
 * be aimed at another user's money.
 */
@WebMvcTest(controllers = PayoutController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class PayoutControllerTest {

    private static final Long CREATOR_ID = 3L;
    private static final Long ADMIN_ID = 9L;
    private static final Long PAYOUT_ID = 20L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PayoutService payoutService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private RequestPostProcessor creator() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(CREATOR_ID)));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(ADMIN_ID)))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void quoteReportsTheRealMaximumRatherThanTheBalance() throws Exception {
        when(payoutService.quote(CREATOR_ID))
                .thenReturn(
                        new PayoutQuoteResponse(
                                new BigDecimal("9300.00"),
                                new BigDecimal("200.00"),
                                new BigDecimal("9100.00"),
                                true,
                                true));

        mockMvc.perform(get("/api/v1/payouts/quote").with(creator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.available").value(9300.00))
                .andExpect(jsonPath("$.responseData.maxWithdrawable").value(9100.00));

        // Scoped to the token's subject — there is no parameter that could quote someone else.
        verify(payoutService).quote(CREATOR_ID);
    }

    @Test
    void requestingAWithdrawalIsAttributedToTheToken() throws Exception {
        when(payoutService.requestPayout(anyLong(), any()))
                .thenReturn(
                        new PayoutResponse(
                                PAYOUT_ID,
                                new BigDecimal("5000.00"),
                                new BigDecimal("200.00"),
                                new BigDecimal("5200.00"),
                                PayoutStatus.REQUESTED,
                                "FNJW0123456789abcdef",
                                null,
                                Instant.parse("2026-09-07T10:00:00Z"),
                                null));

        mockMvc.perform(
                        post("/api/v1/payouts")
                                .with(creator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":5000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.responseData.status").value("REQUESTED"))
                .andExpect(jsonPath("$.responseData.totalDeducted").value(5200.00));

        verify(payoutService).requestPayout(eq(CREATOR_ID), any(RequestPayoutRequest.class));
    }

    @Test
    void withdrawingRequiresAToken() throws Exception {
        mockMvc.perform(
                        post("/api/v1/payouts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":5000.00}"))
                .andExpect(status().isUnauthorized());

        verify(payoutService, never()).requestPayout(anyLong(), any());
    }

    @Test
    void rejectsAWithdrawalFinerThanKobo() throws Exception {
        // CyberPay is instructed in integer kobo, so a third decimal place has nowhere to go.
        mockMvc.perform(
                        post("/api/v1/payouts")
                                .with(creator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":5000.005}"))
                .andExpect(status().isBadRequest());

        verify(payoutService, never()).requestPayout(anyLong(), any());
    }

    @Test
    void rejectsABankAccountThatIsNotANuban() throws Exception {
        mockMvc.perform(
                        put("/api/v1/payouts/bank-account")
                                .with(creator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"accountNumber\":\"12345\",\"bankCode\":\"058\","
                                                + "\"bankName\":\"GTBank\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("01"));

        verify(payoutService, never()).linkBankAccount(anyLong(), any());
    }

    @Test
    void linkingReturnsTheMaskedAccount() throws Exception {
        when(payoutService.linkBankAccount(anyLong(), any()))
                .thenReturn(
                        new BankAccountResponse(
                                5L,
                                "ADESANYA OLUWATOBI JOSEPH",
                                "******6789",
                                "058",
                                "GTBank",
                                true,
                                Instant.parse("2026-09-07T10:00:00Z")));

        mockMvc.perform(
                        put("/api/v1/payouts/bank-account")
                                .with(creator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"accountNumber\":\"0123456789\",\"bankCode\":\"058\","
                                                + "\"bankName\":\"GTBank\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.accountNumber").value("******6789"))
                .andExpect(jsonPath("$.responseData.verified").value(true));
    }

    @Test
    void aCreatorCannotSettleTheirOwnPayout() throws Exception {
        // Marking a payout paid is what stops the reserve ever coming back. A creator reaching it
        // could confirm a transfer that never happened; SecurityConfig's /admin/** rule is what
        // prevents it, and it only applies with the filter chain switched on.
        mockMvc.perform(post("/api/v1/payouts/admin/" + PAYOUT_ID + "/paid").with(creator()))
                .andExpect(status().isForbidden());

        verify(payoutService, never()).markPaid(anyLong());
    }

    @Test
    void aCreatorCannotFailAPayoutToReclaimTheMoney() throws Exception {
        mockMvc.perform(post("/api/v1/payouts/admin/" + PAYOUT_ID + "/failed").with(creator()))
                .andExpect(status().isForbidden());

        verify(payoutService, never()).markFailed(anyLong(), any());
    }

    @Test
    void anAdminCanSettleAPayout() throws Exception {
        when(payoutService.markPaid(PAYOUT_ID))
                .thenReturn(
                        new PayoutResponse(
                                PAYOUT_ID,
                                new BigDecimal("5000.00"),
                                new BigDecimal("200.00"),
                                new BigDecimal("5200.00"),
                                PayoutStatus.PAID,
                                "FNJW0123456789abcdef",
                                null,
                                Instant.parse("2026-09-07T10:00:00Z"),
                                Instant.parse("2026-09-07T11:00:00Z")));

        mockMvc.perform(post("/api/v1/payouts/admin/" + PAYOUT_ID + "/paid").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData.status").value("PAID"));
    }
}
