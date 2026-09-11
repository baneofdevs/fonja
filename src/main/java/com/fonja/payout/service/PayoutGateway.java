package com.fonja.payout.service;

import com.fonja.common.exception.ApiException;
import com.fonja.payout.dto.PayoutDispatch;
import com.fonja.payout.dto.PayoutInstruction;
import java.util.Optional;

/**
 * What the payout module needs from a bank-transfer provider, and nothing more.
 *
 * <p>An interface rather than a direct dependency on CyberPay for the same reason {@code
 * StorageService} is one: the provider is a decision that can change, and the module should not have
 * to. It also keeps {@code PayoutService} testable without an HTTP client — the money rules are the
 * part worth testing, and they should not need a gateway to exercise.
 */
public interface PayoutGateway {

    /**
     * Whether this gateway can currently send anything.
     *
     * <p>Checked before dispatch rather than discovered by exception, so a deployment with no payout
     * credentials logs one line at startup instead of a stack trace per withdrawal.
     */
    boolean isAvailable();

    /**
     * Asks the beneficiary's bank who owns an account, before any money is aimed at it.
     *
     * <p>Returns empty when the gateway cannot answer — unconfigured, unreachable, or the account
     * simply not found. The caller treats that as "unverified", not as a failure: a creator must
     * still be able to link an account when CyberPay is down, and the transfer itself is validated
     * against the name at NIBSS regardless.
     */
    Optional<String> lookupAccountName(String bankCode, String accountNumber);

    /**
     * Instructs a transfer.
     *
     * <p><strong>Not idempotent, and cannot be made so from this side.</strong> The {@code
     * merchantReference} is what lets the gateway recognise a duplicate, but nothing here can promise
     * they will. A caller that retries a timed-out instruction risks paying twice; the only safe
     * response to an uncertain dispatch is to re-query, never to re-send.
     *
     * @throws ApiException gatewayUnavailable if the gateway could not be reached at all — which,
     *     unlike a refusal, leaves the instruction's fate genuinely unknown
     */
    PayoutDispatch send(PayoutInstruction instruction);
}
