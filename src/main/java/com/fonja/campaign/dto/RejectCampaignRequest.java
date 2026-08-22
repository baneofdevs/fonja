package com.fonja.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A rejection reason is mandatory — the creator needs to know what to fix before resubmitting. */
public record RejectCampaignRequest(
        @NotBlank(message = "rejection reason is required")
                @Size(max = 255, message = "rejection reason must be at most 255 characters")
                String reason) {}
