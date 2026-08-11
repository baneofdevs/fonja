package com.fonja.common.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Warns about configuration that is missing but not fatal.
 *
 * <p>The JWT signing key is handled differently — {@link SecurityConfig} refuses
 * to start without it, because running with a guessable key is worse than not
 * running. Google credentials are not in that category: without them the API
 * still works, only the sign-in handshake does not, so the app starts and says
 * so rather than blocking a developer working on another module.
 */
@Component
public class StartupConfigCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigCheck.class);
    private static final String UNCONFIGURED = "google-client-id-not-configured";

    private final String googleClientId;

    public StartupConfigCheck(
            @Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId) {
        this.googleClientId = googleClientId;
    }

    @PostConstruct
    void warnAboutIncompleteConfiguration() {
        if (UNCONFIGURED.equals(googleClientId)) {
            log.warn("Google OAuth2 is not configured: GOOGLE_CLIENT_ID is unset. "
                    + "/api/** still validates existing tokens, but /oauth2/authorization/google "
                    + "will fail. Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env to enable sign-in.");
        }
    }
}
