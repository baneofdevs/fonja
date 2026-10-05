package com.fonja.donation.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.donation.dto.DonationResponse;
import com.fonja.donation.dto.DonationSummaryResponse;
import com.fonja.donation.dto.InitiateDonationRequest;
import com.fonja.donation.dto.InitiateDonationResponse;
import com.fonja.donation.service.DonationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donating needs a signed-in account and nothing more — no verification, no creator role. Only
 * raising money is gated (technical spec §5.4).
 */
@RestController
@RequestMapping("/api/v1/donations")
public class DonationController {

    private static final int MAX_PAGE_SIZE = 100;

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

    /** The signed-in user's own giving history, any status. */
    @GetMapping("/me")
    public CustomResponse<Page<DonationResponse>> myDonations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(
                donationService.myDonations(JwtService.userIdFrom(jwt), pageable(page, size)));
    }

    /** Settled donations to campaigns the signed-in user created. */
    @GetMapping("/received")
    public CustomResponse<Page<DonationResponse>> received(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(
                donationService.receivedDonations(
                        JwtService.userIdFrom(jwt), pageable(page, size)));
    }

    @GetMapping("/summary")
    public CustomResponse<DonationSummaryResponse> summary(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(donationService.summary(JwtService.userIdFrom(jwt)));
    }

    @GetMapping("/{donationId}")
    public CustomResponse<DonationResponse> getById(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long donationId) {
        return CustomResponse.success(
                donationService.getForDonor(JwtService.userIdFrom(jwt), donationId));
    }

    /**
     * Called by the page the donor lands on after checkout. Asks CyberPay for a verdict if the
     * webhook has not delivered one yet, and returns the donation as it stands.
     */
    @PostMapping("/{donationId}/confirm")
    public CustomResponse<DonationResponse> confirm(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long donationId) {
        return CustomResponse.success(
                donationService.confirm(JwtService.userIdFrom(jwt), donationId));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
