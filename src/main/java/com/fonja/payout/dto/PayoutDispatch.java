package com.fonja.payout.dto;

/**
 * What the gateway said when handed a {@link PayoutInstruction}.
 *
 * <p><strong>{@code accepted} is not {@code paid}.</strong> CyberPay answers a successful
 * instruction with {@code Code: "PR"} — "your request is being processed" — so all this records is
 * that they took the instruction and gave it a reference. Whether the money reached the creator's
 * bank is a separate question with a separate answer.
 *
 * @param gatewayReference CyberPay's reference for the transfer, carried in their {@code Data}
 *     field as a bare string rather than an object. Null when the instruction was refused
 * @param message their own wording, which is the only diagnosis a refusal comes with
 */
public record PayoutDispatch(boolean accepted, String gatewayReference, String message) {

    public static PayoutDispatch accepted(String gatewayReference) {
        return new PayoutDispatch(true, gatewayReference, null);
    }

    public static PayoutDispatch refused(String message) {
        return new PayoutDispatch(false, null, message);
    }
}
