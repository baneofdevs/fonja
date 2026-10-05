package com.fonja.user.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.user.dto.CodeSentResponse;
import com.fonja.user.dto.EmailRequest;
import com.fonja.user.dto.LoginRequest;
import com.fonja.user.dto.LoginResponse;
import com.fonja.user.dto.ResetPasswordRequest;
import com.fonja.user.dto.SignupRequest;
import com.fonja.user.dto.VerifyEmailRequest;
import com.fonja.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email-and-password authentication. Every route is public — see {@code SecurityConfig} — because
 * each one is how a caller without a token gets one.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public CustomResponse<CodeSentResponse> signup(@Valid @RequestBody SignupRequest request) {
        return CustomResponse.success(
                "We've sent a verification code to your email", authService.signup(request));
    }

    @PostMapping("/verify-email")
    public CustomResponse<LoginResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request) {
        return CustomResponse.success("Email verified", authService.verifyEmail(request));
    }

    @PostMapping("/resend-code")
    public CustomResponse<CodeSentResponse> resendCode(@Valid @RequestBody EmailRequest request) {
        return CustomResponse.success(
                "If that email needs verifying, a new code is on its way",
                authService.resendCode(request.email()));
    }

    @PostMapping("/login")
    public CustomResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return CustomResponse.success(authService.login(request));
    }

    @PostMapping("/forgot-password")
    public CustomResponse<Void> forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.forgotPassword(request.email());
        return CustomResponse.success(
                "If an account exists for that email, a reset link is on its way", null);
    }

    @PostMapping("/reset-password")
    public CustomResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return CustomResponse.success("Your password has been reset. Log in to continue.", null);
    }
}
