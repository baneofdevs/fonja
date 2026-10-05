package com.fonja.user.entity;

public enum AuthTokenPurpose {
    /** A six-digit code emailed at sign-up (or on login before the address is proved). */
    EMAIL_VERIFICATION,
    /** A long random token in a password-reset link. */
    PASSWORD_RESET
}
