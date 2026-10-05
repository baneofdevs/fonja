package com.fonja.campaign.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An image uploaded for use on a campaign — a cover or a gallery picture.
 *
 * <p>Uploaded before the campaign exists, so it is owned by a user rather than attached to a
 * campaign: the create form uploads the cover first and then sends its URL as {@code
 * coverMediaPath}. The row is what makes the file public; see {@code V14__campaign_media.sql}.
 */
@Entity
@Table(name = "campaign_media")
@Getter
public class CampaignMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "file_reference", nullable = false, length = 512)
    private String fileReference;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CampaignMedia() {
        // JPA
    }

    public CampaignMedia(User owner, String fileReference, String contentType) {
        this.owner = owner;
        this.fileReference = fileReference;
        this.contentType = contentType;
    }
}
