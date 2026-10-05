package com.fonja.user.entity;

/**
 * How someone said they will use Fonja during sign-up. A preference that shapes what the app shows
 * first — it grants nothing: raising funds still needs creator verification, which is what grants
 * {@link Role#CREATOR}.
 */
public enum AccountIntent {
    /** "I want to raise funds." */
    RAISE,
    /** "I want to support a cause." */
    SUPPORT,
    /** Both. */
    BOTH
}
