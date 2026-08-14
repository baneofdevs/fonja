package com.fonja.campaign.entity;

public enum CampaignLifecycleState {
    DRAFT,
    PENDING_REVIEW,
    ACTIVE,
    COMPLETED, // ACTIVE -> COMPLETED: goal reached
    CANCELLED, // ACTIVE -> CANCELLED: creator ended it early
    EXPIRED, // ACTIVE -> EXPIRED: deadline passed unfunded
    SUSPENDED // ACTIVE or PENDING_REVIEW -> SUSPENDED: fraud response

}