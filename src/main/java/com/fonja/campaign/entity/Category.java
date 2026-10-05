package com.fonja.campaign.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A campaign category. Rows rather than an enum so admins can add one without a deploy; the slug is
 * the stable key clients filter by, the name is what they show.
 *
 * <p>Categories are never deleted — campaigns point at them — only deactivated, which hides them
 * from pickers while existing campaigns keep rendering their name.
 */
@Entity
@Table(name = "categories")
@Getter
public class Category {

    /** The catch-all. Only campaigns in it may carry a free-text {@code categoryNote}. */
    public static final String OTHERS_SLUG = "others";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60, updatable = false)
    private String slug;

    @Setter
    @Column(nullable = false, length = 100)
    private String name;

    @Setter
    @Column(length = 16)
    private String emoji;

    @Setter
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Category() {
        // JPA
    }

    public Category(String slug, String name, String emoji, int sortOrder) {
        this.slug = slug;
        this.name = name;
        this.emoji = emoji;
        this.sortOrder = sortOrder;
    }

    public boolean isOthers() {
        return OTHERS_SLUG.equals(slug);
    }
}
