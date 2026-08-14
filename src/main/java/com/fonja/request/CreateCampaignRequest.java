package com.fonja.request;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.CampaignCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCampaignRequest(
        @NotBlank(message = "title is required")
        @Size(max = 150, message = "title must be at most 150 characters")
        String title,

        @NotBlank(message = "story is required")
        @Size(max = 20000, message = "story must be at most 20000 characters")
        String story,

        @NotNull(message = "category is required")
        CampaignCategory category,

        @NotNull(message = "beneficiaryType is required")
        BeneficiaryType beneficiaryType,

        @NotBlank(message = "country is required")
        @Size(max = 100, message = "country must be at most 100 characters")
        String country,

        @Size(max = 255, message = "location must be at most 255 characters")
        String location,

        @NotNull(message = "goalAmount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "goalAmount must be greater than 0")
        BigDecimal goalAmount,

        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code, e.g. NGN")
        String currency,

        boolean autoAdjustGoal,

        @NotBlank(message = "coverMediaPath is required")
        @Size(max = 500, message = "coverMediaPath must be at most 500 characters")
        String coverMediaPath
) {
}