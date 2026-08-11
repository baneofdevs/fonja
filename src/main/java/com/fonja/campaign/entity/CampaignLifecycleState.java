package com.fonja.campaign.entity;

public enum CampaignLifecycleState {
    DRAFT,
    PENDING_REVIEW,
    ACTIVE,
    CLOSED,
    SUSPENDED // added only once fraud response tooling exists
}
