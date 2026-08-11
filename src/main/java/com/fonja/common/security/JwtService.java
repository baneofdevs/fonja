package com.fonja.common.security;

import com.fonja.common.config.OAuth2Config;
import com.fonja.user.entity.Role;
import com.fonja.user.entity.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Issues and reads the internal JWT. The provider `sub` is used exactly once,
 * at login, to resolve the Fonja user; from then on every request carries a
 * token whose subject is the internal {@code user_id}.
 */
@Service
public class JwtService {

    /** Claim carrying the user's roles, read back as authorities by the resource server. */
    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder jwtEncoder;
    private final OAuth2Config authConfig;

    public JwtService(JwtEncoder jwtEncoder, OAuth2Config authConfig) {
        this.jwtEncoder = jwtEncoder;
        this.authConfig = authConfig;
    }

    public String issueToken(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Role::name).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(authConfig.getJwtIssuer())
                .issuedAt(now)
                .expiresAt(now.plus(authConfig.getJwtTtlSeconds(), ChronoUnit.SECONDS))
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("verified", user.isVerified())
                .claim(ROLES_CLAIM, roles)
                .build();

        // The header is explicit: NimbusJwtEncoder defaults to RS256, which does
        // not match the HMAC key this service signs with.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * The internal user id from a validated token. The subject is written by
     * {@link #issueToken} and the signature is checked before this is reached,
     * so a malformed subject means a bug on our side, not bad client input.
     */
    public static Long userIdFrom(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
