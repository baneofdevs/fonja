package com.fonja.campaign.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "campaigns")

@Getter
@Setter
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

    // Excluded from @Setter — state only ever moves through the guarded
    // transition methods below, never a direct assignment.
    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false)
    private CampaignLifecycleState lifecycleState;

    // Set by reject(), cleared by submitForReview() — only meaningful while
    // the campaign sits back in DRAFT after a rejected review.
    @Setter(AccessLevel.NONE)
    @Column(name = "rejection_reason")
    private String rejectionReason;

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

    /**
     * Creates a new draft campaign. Every campaign starts in {@link CampaignLifecycleState#DRAFT}
     * — submission to review and approval to active happen as separate, later transitions.
     */
    public Campaign(User creator, String title, String story, CampaignCategory category,
                    BeneficiaryType beneficiaryType, String country, String location,
                    BigDecimal goalAmount, String currency, boolean autoAdjustGoal, String coverMediaPath) {
        this.creator = creator;
        this.title = title;
        this.story = story;
        this.category = category;
        this.beneficiaryType = beneficiaryType;
        this.country = country;
        this.location = location;
        this.goalAmount = goalAmount;
        if (currency != null && !currency.isBlank()) {
            this.currency = currency;
        }
        this.autoAdjustGoal = autoAdjustGoal;
        this.coverMediaPath = coverMediaPath;
        this.lifecycleState = CampaignLifecycleState.DRAFT;
    }

    /** DRAFT -> PENDING_REVIEW: creator submits the draft for admin review. */
    public void submitForReview() {
        requireState(CampaignLifecycleState.DRAFT);
        this.lifecycleState = CampaignLifecycleState.PENDING_REVIEW;
        this.submittedAt = Instant.now();
        this.rejectionReason = null;
    }

    /** PENDING_REVIEW -> ACTIVE: admin approves, campaign goes live on the feed. */
    public void activate() {
        requireState(CampaignLifecycleState.PENDING_REVIEW);
        this.lifecycleState = CampaignLifecycleState.ACTIVE;
        this.approvedAt = Instant.now();
    }

    /** PENDING_REVIEW -> DRAFT: admin rejects; creator can revise and resubmit. */
    public void reject(String reason) {
        requireState(CampaignLifecycleState.PENDING_REVIEW);
        this.lifecycleState = CampaignLifecycleState.DRAFT;
        this.rejectionReason = reason;
    }

    /** ACTIVE -> COMPLETED: goal reached. */
    public void complete() {
        requireState(CampaignLifecycleState.ACTIVE);
        this.lifecycleState = CampaignLifecycleState.COMPLETED;
    }

    /**
     * PENDING_REVIEW or ACTIVE -> CANCELLED: creator withdraws the campaign,
     * whether it's awaiting review or already live. Also the soft-delete
     * target for a posted campaign — see CampaignService.deleteCampaign.
     */
    public void cancel() {
        requireState(CampaignLifecycleState.PENDING_REVIEW, CampaignLifecycleState.ACTIVE);
        this.lifecycleState = CampaignLifecycleState.CANCELLED;
    }

    /** ACTIVE -> EXPIRED: deadline passed unfunded. */
    public void expire() {
        requireState(CampaignLifecycleState.ACTIVE);
        this.lifecycleState = CampaignLifecycleState.EXPIRED;
    }

    private void requireState(CampaignLifecycleState... expected) {
        for (CampaignLifecycleState state : expected) {
            if (lifecycleState == state) {
                return;
            }
        }
        throw new IllegalStateException(
                "Campaign " + id + " is " + lifecycleState + ", expected one of "
                        + java.util.Arrays.toString(expected));
    }
}
