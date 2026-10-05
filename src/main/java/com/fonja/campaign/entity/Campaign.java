package com.fonja.campaign.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A fundraising campaign.
 *
 * <p>Setters are deliberately per-field rather than class-level: only the content fields a creator
 * edits are settable. Identity ({@code id}, {@code creator}), lifecycle state, and the timestamps
 * that record when transitions happened are writable only through the guarded transition methods
 * below, so no caller can put a campaign into a state it could not legally reach.
 */
@Entity
@Table(name = "campaigns")
@Getter
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Setter
    @Column(nullable = false)
    private String title;

    @Setter
    @Lob
    @Column(columnDefinition = "TEXT")
    private String story;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** Free-text label, only when {@link #category} is "others" — the creator's own word for it. */
    @Setter
    @Column(name = "category_note", length = 60)
    private String categoryNote;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type")
    private BeneficiaryType beneficiaryType;

    @Setter
    @Column(nullable = false)
    private String country;

    @Setter @Column private String location;

    @Setter
    @Column(name = "goal_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal goalAmount;

    @Setter
    @Column(nullable = false, length = 3)
    private String currency = "NGN";

    @Setter
    @Column(name = "auto_adjust_goal", nullable = false)
    private boolean autoAdjustGoal = false;

    @Setter
    @Column(name = "cover_media_path")
    private String coverMediaPath;

    /** When the campaign stops accepting donations. {@code null} means open-ended. */
    @Setter
    @Column(name = "deadline")
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false)
    private CampaignLifecycleState lifecycleState;

    // Set by reject(), cleared by submitForReview() — only meaningful while
    // the campaign sits back in DRAFT after a rejected review.
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
     * Creates a new draft campaign. Every campaign starts in {@link CampaignLifecycleState#DRAFT} —
     * submission to review and approval to active happen as separate, later transitions.
     */
    public Campaign(
            User creator,
            String title,
            String story,
            Category category,
            String categoryNote,
            BeneficiaryType beneficiaryType,
            String country,
            String location,
            BigDecimal goalAmount,
            String currency,
            boolean autoAdjustGoal,
            String coverMediaPath,
            Instant deadline) {
        this.creator = creator;
        this.title = title;
        this.story = story;
        this.category = category;
        this.categoryNote = categoryNote;
        this.beneficiaryType = beneficiaryType;
        this.country = country;
        this.location = location;
        this.goalAmount = goalAmount;
        if (currency != null && !currency.isBlank()) {
            this.currency = currency;
        }
        this.autoAdjustGoal = autoAdjustGoal;
        this.coverMediaPath = coverMediaPath;
        this.deadline = deadline;
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

    /**
     * ACTIVE -> COMPLETED: goal reached.
     *
     * <p>Not yet called from anywhere: the caller is the donation path, which credits a campaign
     * and completes it once the goal is met. That lands with the {@code donation} module (technical
     * spec Day 3) — {@code DonationService} is still a stub.
     */
    public void complete() {
        requireState(CampaignLifecycleState.ACTIVE);
        this.lifecycleState = CampaignLifecycleState.COMPLETED;
    }

    /**
     * PENDING_REVIEW or ACTIVE -> CANCELLED: creator withdraws the campaign, whether it's awaiting
     * review or already live. Also the soft-delete target for a posted campaign — see
     * CampaignService.deleteCampaign.
     */
    public void cancel() {
        requireState(CampaignLifecycleState.PENDING_REVIEW, CampaignLifecycleState.ACTIVE);
        this.lifecycleState = CampaignLifecycleState.CANCELLED;
    }

    /** ACTIVE -> EXPIRED: deadline passed unfunded. Driven by CampaignExpiryJob. */
    public void expire() {
        requireState(CampaignLifecycleState.ACTIVE);
        this.lifecycleState = CampaignLifecycleState.EXPIRED;
    }

    /** True once the campaign is in a terminal state — nothing left to edit, delete, or fund. */
    public boolean hasEnded() {
        return lifecycleState == CampaignLifecycleState.COMPLETED
                || lifecycleState == CampaignLifecycleState.CANCELLED
                || lifecycleState == CampaignLifecycleState.EXPIRED;
    }

    private void requireState(CampaignLifecycleState... expected) {
        for (CampaignLifecycleState state : expected) {
            if (lifecycleState == state) {
                return;
            }
        }
        throw new IllegalCampaignStateException(
                "Campaign "
                        + id
                        + " is "
                        + lifecycleState
                        + ", expected one of "
                        + Arrays.toString(expected));
    }
}
