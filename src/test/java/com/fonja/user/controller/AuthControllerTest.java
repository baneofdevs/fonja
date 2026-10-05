package com.fonja.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fonja.common.config.SecurityConfig;
import com.fonja.common.exception.ApiException;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import com.fonja.user.dto.CodeSentResponse;
import com.fonja.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Against the real {@link SecurityConfig}: these routes must work with no token at all. */
@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "fonja.auth.jwt-secret=test-only-signing-secret-at-least-32-bytes",
            "spring.security.oauth2.client.registration.google.client-id=test-client",
            "spring.security.oauth2.client.registration.google.client-secret=test-secret"
        })
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AuthService authService;

    /** Only present so SecurityConfig's OAuth2 login chain can be built; never invoked here. */
    @MockitoBean private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private static final String VALID_SIGNUP =
            """
            {"firstName":"Ada","lastName":"Obi","email":"ada@example.com",
             "phone":"08031234567","password":"correct horse","acceptedTerms":true}
            """;

    @Test
    void signupNeedsNoToken() throws Exception {
        when(authService.signup(any())).thenReturn(new CodeSentResponse("ada@example.com", 60));

        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_SIGNUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"))
                .andExpect(jsonPath("$.responseData.email").value("ada@example.com"));
    }

    @Test
    void signupWithoutAcceptingTheTermsIsAValidationError() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_SIGNUP.replace("true", "false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("01"));
        verify(authService, never()).signup(any());
    }

    @Test
    void aShortPasswordIsAValidationError() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_SIGNUP.replace("correct horse", "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCodeThatIsNotSixDigitsNeverReachesTheService() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"ada@example.com\",\"code\":\"12a456\"}"))
                .andExpect(status().isBadRequest());
        verify(authService, never()).verifyEmail(any());
    }

    @Test
    void anUnverifiedLoginSurfacesCode07() throws Exception {
        when(authService.login(any()))
                .thenThrow(ApiException.emailNotVerified("Verify your email to continue."));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"ada@example.com\",\"password\":\"pw\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.responseCode").value("07"));
    }

    @Test
    void forgotPasswordAnswersTheSameWhateverTheEmail() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("00"));
    }
}
