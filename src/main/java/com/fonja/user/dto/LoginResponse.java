package com.fonja.user.dto;

/** What every successful sign-in returns — OAuth2, password, or a verified sign-up code. */
public record LoginResponse(String accessToken, UserResponse user) {}
