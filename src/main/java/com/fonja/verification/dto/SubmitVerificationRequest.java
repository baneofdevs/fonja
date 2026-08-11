package com.fonja.verification.dto;

import com.fonja.verification.entity.VerificationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param registrationNumber CAC/registration number. Required for BUSINESS and
 *                           NGO, ignored for INDIVIDUAL — enforced in the
 *                           service, since the rule depends on {@code type}.
 */
public record SubmitVerificationRequest(
        @NotNull(message = "verification type is required")
        VerificationType type,

        @Size(max = 255, message = "registration number must be at most 255 characters")
        String registrationNumber
) {
}
