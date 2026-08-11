package com.fonja.common.security;

import com.fonja.common.config.OAuth2Config;
import com.fonja.common.response.CustomResponse;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
// Spring Boot 4 autoconfigures Jackson 3, whose databind lives under
// `tools.jackson` — Jackson 2 is on the classpath only transitively and has no bean.
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;

/**
 * The hand-off point between the provider's identity and ours: maps the
 * provider `sub` to an internal user (creating it on first login) and issues
 * the internal JWT that every subsequent {@code /api/**} call carries.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserService userService;
    private final JwtService jwtService;
    private final OAuth2Config authConfig;
    private final ObjectMapper objectMapper;

    public OAuth2LoginSuccessHandler(UserService userService, JwtService jwtService,
                                     OAuth2Config authConfig, ObjectMapper objectMapper) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.authConfig = authConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OAuth2User principal = token.getPrincipal();
        AuthProvider provider = resolveProvider(token.getAuthorizedClientRegistrationId());

        User user = userService.upsertFromOAuth2(
                provider,
                principal.getName(),                       // the `sub` claim
                principal.getAttribute("email"),
                principal.getAttribute("name")
        );

        String jwt = jwtService.issueToken(user);
        log.info("OAuth2 login succeeded for user id={} via provider={}", user.getId(), provider);

        String redirectUri = authConfig.getLoginSuccessRedirectUri();
        if (redirectUri != null && !redirectUri.isBlank()) {
            response.sendRedirect(UriComponentsBuilder.fromUriString(redirectUri)
                    .queryParam("token", jwt)
                    .build()
                    .toUriString());
            return;
        }

        // No frontend configured yet — hand the token straight back so the flow
        // is testable end-to-end from a browser.
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                CustomResponse.success(new LoginResponse(jwt, UserResponse.from(user))));
    }

    private AuthProvider resolveProvider(String registrationId) {
        return AuthProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
    }

    public record LoginResponse(String accessToken, UserResponse user) {
    }
}
