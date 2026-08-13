package com.fonja.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fonja's own auth settings, bound from {@code fonja.auth.*}.
 *
 * <p>Provider client registrations themselves live in {@code
 * spring.security.oauth2.client.registration.*} — Spring Boot binds those natively, so duplicating
 * them here would only create a second source of truth.
 *
 * <p>NOTE: Apple Sign-In requires a JWT-based client secret that expires (six months maximum)
 * rather than a static one, so adding the Apple registration means adding secret generation and
 * rotation. Google is the Day 1 provider.
 */
@ConfigurationProperties(prefix = "fonja.auth")
public class OAuth2Config {

    /**
     * HMAC signing key for the internal JWT. Must be at least 32 bytes for HS256. There is no
     * default — the app refuses to start without it, which is the point: a fallback here would ship
     * a publicly-known signing key.
     */
    private String jwtSecret;

    /** Internal JWT lifetime in seconds. Default 12 hours. */
    private long jwtTtlSeconds = 43_200;

    /** {@code iss} claim on issued tokens. */
    private String jwtIssuer = "fonja";

    /**
     * Where to send the browser after a successful OAuth2 handshake, with the internal JWT appended
     * as a {@code token} query parameter. Leave unset and the handler writes the token as a JSON
     * body instead — which is how the flow is smoke-tested before a frontend exists.
     */
    private String loginSuccessRedirectUri;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getJwtTtlSeconds() {
        return jwtTtlSeconds;
    }

    public void setJwtTtlSeconds(long jwtTtlSeconds) {
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }

    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    public String getLoginSuccessRedirectUri() {
        return loginSuccessRedirectUri;
    }

    public void setLoginSuccessRedirectUri(String loginSuccessRedirectUri) {
        this.loginSuccessRedirectUri = loginSuccessRedirectUri;
    }
}
