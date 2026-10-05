package com.fonja.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateInterestsRequest(
        @NotNull(message = "interests is required")
                @Size(max = 20, message = "Pick at most 20 interests")
                List<String> interests) {}
