package com.fonja.user.dto;

import com.fonja.user.entity.AccountIntent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The sign-up steps after the email is verified: how you'll use Fonja, and what you care about. */
public record OnboardingRequest(
        @NotNull(message = "accountIntent is required") AccountIntent accountIntent,
        @Size(max = 20, message = "Pick at most 20 interests") List<String> interests) {}
