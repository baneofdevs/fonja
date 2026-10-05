package com.fonja.campaign.entity;

/**
 * Campaign lifecycle (technical spec §4.3).
 *
 * <pre>
 * DRAFT -> PENDING_REVIEW -> ACTIVE -> COMPLETED   (goal reached)
 *   ^            |             |
 *   |            |             +----> EXPIRED      (deadline passed unfunded)
 *   +--- reject -+             |
 *                +-------------+----> CANCELLED    (creator withdrew)
 * </pre>
 *
 * <p>Every transition runs through a guarded method on {@link Campaign}; nothing assigns this field
 * directly.
 */
public enum CampaignLifecycleState {
    DRAFT,
    PENDING_REVIEW,
    ACTIVE,
    COMPLETED, // ACTIVE -> COMPLETED: goal reached
    CANCELLED, // PENDING_REVIEW or ACTIVE -> CANCELLED: creator ended it early
    EXPIRED, // ACTIVE -> EXPIRED: deadline passed unfunded
    SUSPENDED; // fraud response; no transition into it yet, tooling not built

    /**
     * Whether anyone, signed in or not, may read a campaign in this state.
     *
     * <p>ACTIVE is the feed. COMPLETED and EXPIRED stay readable because their links are already
     * out in the world — a donor returning to a campaign that just hit its goal should see that,
     * not a 404. Everything else is either not yet approved or was withdrawn, and reads as absent
     * to anyone but its creator.
     */
    public boolean isPubliclyVisible() {
        return this == ACTIVE || this == COMPLETED || this == EXPIRED;
    }
}
