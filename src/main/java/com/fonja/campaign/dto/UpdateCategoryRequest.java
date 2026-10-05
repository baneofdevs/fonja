package com.fonja.campaign.dto;

import jakarta.validation.constraints.Size;

/** Partial update; a null field is left untouched. The slug is permanent — clients filter by it. */
public record UpdateCategoryRequest(
        @Size(min = 1, max = 100, message = "name must be 1 to 100 characters") String name,
        @Size(max = 16, message = "emoji must be at most 16 characters") String emoji,
        Integer sortOrder,
        Boolean active) {}
