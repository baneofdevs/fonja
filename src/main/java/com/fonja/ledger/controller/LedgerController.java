package com.fonja.ledger.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.ledger.dto.EarningsResponse;
import com.fonja.ledger.service.LedgerService;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only. The ledger is written by {@link LedgerService} in response to confirmed donations and
 * withdrawal requests — there is no endpoint that writes an entry, and there should never be one.
 */
@RestController
@RequestMapping("/api/v1/earnings")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * The caller's own earnings, summed from their entries on every request. Scoped to the token's
     * subject rather than taking an id, so there is no way to read someone else's balance.
     */
    @GetMapping
    public CustomResponse<EarningsResponse> myEarnings(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(ledgerService.getEarnings(JwtService.userIdFrom(jwt)));
    }

    /**
     * Fonja's own revenue. Admin-only via {@code SecurityConfig}'s {@code /api/v1/*}{@code
     * /admin/**} rule.
     */
    @GetMapping("/admin/platform-revenue")
    public CustomResponse<BigDecimal> platformRevenue() {
        return CustomResponse.success(ledgerService.getPlatformRevenue());
    }
}
