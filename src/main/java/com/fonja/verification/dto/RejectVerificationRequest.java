package com.fonja.verification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A rejection reason is mandatory — the creator has to know what to fix before
 * resubmitting, and an unexplained rejection is a support ticket waiting to happen.
 */
public record RejectVerificationRequest(
        @NotBlank(message = "rejection reason is required")
        @Size(max = 255, message = "rejection reason must be at most 255 characters")
        String reason
) {
}
