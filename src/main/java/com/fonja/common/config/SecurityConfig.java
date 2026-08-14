package com.fonja.common.config;

import com.fonja.common.security.JwtService;
import com.fonja.common.security.OAuth2LoginSuccessHandler;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two chains, deliberately separate:
 *
 * <ul>
 *   <li>{@code /api/**} is stateless and authenticates with the internal JWT only.
 *   <li>The OAuth2 handshake endpoints need a session to hold the authorization request between the
 *       redirect out and the callback back, so they cannot live under a stateless policy.
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OAuth2Config.class)
public class SecurityConfig {

    /** HS256 needs a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final OAuth2Config authConfig;

    public SecurityConfig(OAuth2Config authConfig) {
        this.authConfig = authConfig;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        return http.securityMatcher("/api/**")
                // No browser session, no cookies, no CSRF surface: every /api call
                // carries a bearer token instead.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // CyberPay posts here with a gateway signature, not a user token.
                        // Signature verification lands with the webhook itself on Day 3.
                        .requestMatchers("/api/v1/donations/webhook/**").permitAll()
                        .requestMatchers("/api/v1/verification/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/campaigns/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain loginFilterChain(
            HttpSecurity http, OAuth2LoginSuccessHandler successHandler) throws Exception {
        return http.authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/actuator/health/**",
                                                "/oauth2/**",
                                                "/login/**",
                                                "/error")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2Login(oauth2 -> oauth2.successHandler(successHandler))
                .build();
    }

    /**
     * Maps the {@code roles} claim onto {@code ROLE_*} authorities, which is what {@code
     * hasRole("ADMIN")} above matches against.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(signingKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private SecretKey signingKey() {
        String secret = authConfig.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "fonja.auth.jwt-secret is not set. Set FONJA_AUTH_JWT_SECRET to a random value "
                            + "of at least "
                            + MIN_SECRET_BYTES
                            + " bytes.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // Refuse rather than silently pad: a short key is a weak key.
            throw new IllegalStateException(
                    "fonja.auth.jwt-secret must be at least "
                            + MIN_SECRET_BYTES
                            + " bytes for HS256; got "
                            + keyBytes.length
                            + ".");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
