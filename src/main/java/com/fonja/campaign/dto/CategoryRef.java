package com.fonja.campaign.dto;

import com.fonja.campaign.entity.Category;

/** The slim form of a category embedded in other responses: enough to filter by and to show. */
public record CategoryRef(String slug, String name) {

    public static CategoryRef from(Category category) {
        return category == null ? null : new CategoryRef(category.getSlug(), category.getName());
    }
}
