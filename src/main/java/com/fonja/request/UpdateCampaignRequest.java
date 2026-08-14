package com.fonja.request;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.CampaignCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Partial update — every field is optional. A field left {@code null} is left
 * untouched on the campaign; only the fields actually provided are applied.
 */
public record UpdateCampaignRequest(
        @Size(max = 150, message = "title must be at most 150 characters")
        String title,

        @Size(max = 20000, message = "story must be at most 20000 characters")
        String story,

        CampaignCategory category,

        BeneficiaryType beneficiaryType,

        @Size(max = 100, message = "country must be at most 100 characters")
        String country,

        @Size(max = 255, message = "location must be at most 255 characters")
        String location,

        @DecimalMin(value = "0.0", inclusive = false, message = "goalAmount must be greater than 0")
        BigDecimal goalAmount,

        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code, e.g. NGN")
        String currency,

        Boolean autoAdjustGoal,

        @Size(max = 500, message = "coverMediaPath must be at most 500 characters")
        String coverMediaPath
) {
}