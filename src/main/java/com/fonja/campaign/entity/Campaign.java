package com.fonja.campaign.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String story;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type")
    private BeneficiaryType beneficiaryType;

    @Column(nullable = false)
    private String country;

    @Column private String location;

    @Column(name = "goal_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal goalAmount;

    @Column(nullable = false, length = 3)
    private String currency = "NGN";

    @Column(name = "auto_adjust_goal", nullable = false)
    private boolean autoAdjustGoal = false;

    @Column(name = "cover_media_path")
    private String coverMediaPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false)
    private CampaignLifecycleState lifecycleState;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt; // set on transition to PENDING_REVIEW

    @Column(name = "approved_at")
    private Instant approvedAt; // set on transition to ACTIVE

    protected Campaign() {
        // JPA
    }

    // TODO: constructor, getters/setters per team convention
}
