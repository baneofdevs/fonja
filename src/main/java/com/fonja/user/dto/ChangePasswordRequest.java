package com.fonja.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code currentPassword} is required when the account already has one; an OAuth account setting
 * its first password leaves it null.
 */
public record ChangePasswordRequest(
        String currentPassword,
        @NotBlank(message = "newPassword is required")
                @Size(min = 8, max = 72, message = "newPassword must be 8 to 72 characters")
                String newPassword) {}
