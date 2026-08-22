package com.fonja.ledger.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.common.config.SecurityConfig;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import com.fonja.ledger.dto.EarningsResponse;
import com.fonja.ledger.service.LedgerService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Runs against the real {@link SecurityConfig}. Its {@code /api/v1/*}{@code /admin/**} rule is what
 * keeps platform revenue away from ordinary creators, and that rule is only exercised with the
 * filter chain switched on.
 */
@WebMvcTest(controllers = LedgerController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class LedgerControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LedgerService ledgerService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private RequestPostProcessor creator() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(USER_ID)));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.subject(String.valueOf(ADMIN_ID)))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void earningsAreScopedToTheCallersOwnToken() throws Exception {
        when(ledgerService.getEarnings(USER_ID))
                .thenReturn(
                        new EarningsResponse(
                                new BigDecimal("4100.00"),
                                new BigDecimal("9300.00"),
                                new BigDecimal("5000.00"),
                                new BigDecimal("200.00")));

        mockMvc.perform(get("/api/v1/earnings").with(creator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.responseData.available").value(4100.00));

        // The id comes from the token's subject — there is no path or query parameter that could
        // point this at another creator.
        verify(ledgerService).getEarnings(USER_ID);
    }

    @Test
    void earningsRequireAToken() throws Exception {
        mockMvc.perform(get("/api/v1/earnings")).andExpect(status().isUnauthorized());

        verify(ledgerService, never()).getEarnings(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void platformRevenueIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/earnings/admin/platform-revenue").with(creator()))
                .andExpect(status().isForbidden());

        verify(ledgerService, never()).getPlatformRevenue();
    }

    @Test
    void adminCanReadPlatformRevenue() throws Exception {
        when(ledgerService.getPlatformRevenue()).thenReturn(new BigDecimal("700.00"));

        mockMvc.perform(get("/api/v1/earnings/admin/platform-revenue").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseData").value(700.00));
    }
}
