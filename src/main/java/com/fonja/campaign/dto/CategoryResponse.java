package com.fonja.campaign.dto;

import com.fonja.campaign.entity.Category;

public record CategoryResponse(
        Long id, String slug, String name, String emoji, int sortOrder, boolean active) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getSlug(),
                category.getName(),
                category.getEmoji(),
                category.getSortOrder(),
                category.isActive());
    }
}
