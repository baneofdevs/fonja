package com.fonja.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "token is required") String token,
        @NotBlank(message = "newPassword is required")
                @Size(min = 8, max = 72, message = "newPassword must be 8 to 72 characters")
                String newPassword) {}
