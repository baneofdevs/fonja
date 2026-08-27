package com.fonja.donation.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.donation.dto.InitiateDonationRequest;
import com.fonja.donation.dto.InitiateDonationResponse;
import com.fonja.donation.service.DonationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donating needs a signed-in account and nothing more — no verification, no creator role. Only
 * raising money is gated (technical spec §5.4).
 */
@RestController
@RequestMapping("/api/v1/donations")
public class DonationController {

    private final DonationService donationService;

    public DonationController(DonationService donationService) {
        this.donationService = donationService;
    }

    /**
     * Opens a checkout session. The response carries a {@code checkoutUrl} on CyberPay's domain;
     * the donor enters their card there, never here.
     *
     * <p>The donation is not money in the ledger yet, and this response is not a receipt. It
     * becomes real when CyberPay notifies us and the re-query confirms it — see {@link
     * DonationWebhookController}.
     */
    @PostMapping
    public CustomResponse<InitiateDonationResponse> initiate(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InitiateDonationRequest request) {
        return CustomResponse.success(
                "Checkout ready", donationService.initiate(JwtService.userIdFrom(jwt), request));
    }
}
