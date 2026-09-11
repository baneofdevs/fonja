package com.fonja.payout.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.payout.dto.BankAccountResponse;
import com.fonja.payout.dto.LinkBankAccountRequest;
import com.fonja.payout.dto.PayoutQuoteResponse;
import com.fonja.payout.dto.PayoutResponse;
import com.fonja.payout.dto.RequestPayoutRequest;
import com.fonja.payout.service.PayoutService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Withdrawals, and the bank account they go to.
 *
 * <p>Every creator-facing route is scoped to the token's subject — there is no path or body field
 * anywhere here that names a user, so no request can move someone else's money or read their
 * account. The admin routes sit under {@code /admin}, which {@code SecurityConfig}'s {@code
 * /api/v1/*}{@code /admin/**} rule gates on {@code ROLE_ADMIN} without needing a line of its own.
 */
@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService payoutService;

    public PayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    /**
     * Links or replaces the account withdrawals are sent to. The account name in the response comes
     * from the bank, not from the request.
     */
    @PutMapping("/bank-account")
    public CustomResponse<BankAccountResponse> linkBankAccount(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkBankAccountRequest request) {
        return CustomResponse.success(
                "Bank account linked",
                payoutService.linkBankAccount(JwtService.userIdFrom(jwt), request));
    }

    @GetMapping("/bank-account")
    public CustomResponse<BankAccountResponse> myBankAccount(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(payoutService.getBankAccount(JwtService.userIdFrom(jwt)));
    }

    /**
     * What the caller can withdraw right now, including the real maximum.
     *
     * <p>Worth calling before offering a "withdraw everything" button: the flat fee comes out of the
     * same balance, so the ceiling is always below {@code available}.
     */
    @GetMapping("/quote")
    public CustomResponse<PayoutQuoteResponse> quote(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(payoutService.quote(JwtService.userIdFrom(jwt)));
    }

    /**
     * Requests a withdrawal. The amount and the fee leave the creator's earnings immediately; the
     * transfer itself goes out on the next dispatch run, so a {@code REQUESTED} response means
     * "reserved and queued", not "sent".
     */
    @PostMapping
    public CustomResponse<PayoutResponse> requestPayout(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RequestPayoutRequest request) {
        return CustomResponse.success(
                "Withdrawal requested",
                payoutService.requestPayout(JwtService.userIdFrom(jwt), request));
    }

    @GetMapping
    public CustomResponse<Page<PayoutResponse>> myPayouts(
            @AuthenticationPrincipal Jwt jwt, @PageableDefault(size = 20) Pageable pageable) {
        return CustomResponse.success(
                payoutService.myPayouts(JwtService.userIdFrom(jwt), pageable));
    }

    /** Everything not yet settled, oldest first. */
    @GetMapping("/admin/open")
    public CustomResponse<Page<PayoutResponse>> openPayouts(
            @PageableDefault(size = 20) Pageable pageable) {
        return CustomResponse.success(payoutService.openPayouts(pageable));
    }

    /**
     * Confirms a payout an admin has seen land — the manual counterpart to a gateway confirmation,
     * and the only way to settle one while CyberPay's payout credentials are outstanding.
     */
    @PostMapping("/admin/{payoutId}/paid")
    public CustomResponse<PayoutResponse> markPaid(@PathVariable Long payoutId) {
        return CustomResponse.success("Payout marked paid", payoutService.markPaid(payoutId));
    }

    /** Fails a payout and returns the amount and the fee to the creator's balance. */
    @PostMapping("/admin/{payoutId}/failed")
    public CustomResponse<PayoutResponse> markFailed(
            @PathVariable Long payoutId,
            @RequestParam(defaultValue = "Failed at the gateway") String reason) {
        return CustomResponse.success(
                "Payout marked failed and reversed", payoutService.markFailed(payoutId, reason));
    }
}
