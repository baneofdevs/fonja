package com.fonja.user.dto;

/**
 * Where a code went and how long before another can be asked for. Returned whether or not the
 * address has an account, so the endpoint cannot be used to find out.
 */
public record CodeSentResponse(String email, int resendAfterSeconds) {}
