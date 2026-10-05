package com.fonja.comment.entity;

import com.fonja.campaign.entity.Campaign;
import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A comment on a campaign, or a reply to one. Replies go one level deep: {@link #parentId} points
 * at a top-level comment and nothing points at a reply.
 */
@Entity
@Table(name = "campaign_comments")
@Getter
public class CampaignComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false, updatable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;

    /** The comment this replies to; null for a top-level comment. */
    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    @Column(nullable = false, length = 1000)
    private String body;

    /** Set when the author, the campaign's creator or an admin removes it. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignComment() {
        // JPA
    }

    public CampaignComment(Campaign campaign, User author, Long parentId, String body) {
        this.campaign = campaign;
        this.author = author;
        this.parentId = parentId;
        this.body = body;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isReply() {
        return parentId != null;
    }

    public void delete(Instant at) {
        if (deletedAt == null) {
            deletedAt = at;
        }
    }
}
