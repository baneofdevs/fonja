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
    SUSPENDED // fraud response; no transition into it yet, tooling not built
}
