package com.fonja.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank(message = "slug is required")
                @Size(max = 60, message = "slug must be at most 60 characters")
                @Pattern(
                        regexp = "[a-z0-9]+(-[a-z0-9]+)*",
                        message =
                                "slug must be lowercase words joined by hyphens, e.g. arts-crafts")
                String slug,
        @NotBlank(message = "name is required")
                @Size(max = 100, message = "name must be at most 100 characters")
                String name,
        @Size(max = 16, message = "emoji must be at most 16 characters") String emoji,
        Integer sortOrder) {}
