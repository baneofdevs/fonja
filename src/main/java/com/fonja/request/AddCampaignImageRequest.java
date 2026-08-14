package com.fonja.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCampaignImageRequest(
        @NotBlank(message = "filePath is required")
        @Size(max = 500, message = "filePath must be at most 500 characters")
        String filePath
) {
}
