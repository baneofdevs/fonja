package com.fonja.notification.entity;

/**
 * BRD §13: donations made and received, campaign decisions, verification decisions, withdrawals.
 */
public enum NotificationType {
    /** To the donor: thanks, here is what you gave and to whom. */
    DONATION_RECEIPT,
    /** To the creator: someone just gave to your campaign. */
    DONATION_RECEIVED,
    CAMPAIGN_APPROVED,
    CAMPAIGN_REJECTED,
    /** To the creator: the campaign reached its goal and has stopped taking donations. */
    CAMPAIGN_COMPLETED,
    VERIFICATION_APPROVED,
    VERIFICATION_REJECTED,
    WITHDRAWAL_REQUESTED,
    WITHDRAWAL_CONFIRMED,
    WITHDRAWAL_FAILED
}
