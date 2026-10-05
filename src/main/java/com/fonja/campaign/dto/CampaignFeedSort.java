package com.fonja.campaign.dto;

/** How the public feed is ordered. The default is {@link #NEWEST}. */
public enum CampaignFeedSort {
    /** Most recently approved first — what a visitor sees on the landing page. */
    NEWEST,
    /** Soonest deadline first; open-ended campaigns come last. */
    ENDING_SOON,
    /** Highest settled donation total first. */
    MOST_RAISED
}
