package com.fonja.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Just an address — resending a code, asking for a reset link. */
public record EmailRequest(
        @NotBlank(message = "email is required") @Email(message = "email must be valid")
                String email) {}
