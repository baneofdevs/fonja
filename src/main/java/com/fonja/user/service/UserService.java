package com.fonja.user.service;

import com.fonja.common.exception.ApiException;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.Role;
import com.fonja.user.entity.User;
import com.fonja.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only entry point into the {@code user} module. Other modules call this, never {@link
 * UserRepository} or {@link User} directly.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Resolves an OAuth2 login to a Fonja user, creating the account on first sign-in. Matching is
     * on the provider `sub` first; a user who signed up with the same email under a different
     * provider is a conflict rather than a silent account merge — linking identities is a
     * deliberate feature, not something to fall into by accident.
     */
    @Transactional
    public User upsertFromOAuth2(
            AuthProvider provider, String providerSub, String email, String fullName) {
        if (providerSub == null || providerSub.isBlank()) {
            throw ApiException.badRequest("OAuth2 provider returned no subject identifier");
        }
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("OAuth2 provider returned no email address");
        }

        return userRepository
                .findByAuthProviderAndAuthProviderSub(provider, providerSub)
                .map(existing -> refreshProfile(existing, email, fullName))
                .orElseGet(() -> createFromOAuth2(provider, providerSub, email, fullName));
    }

    private User refreshProfile(User user, String email, String fullName) {
        // The provider is authoritative on both — keep the local copy current.
        if (!email.equals(user.getEmail())) {
            user.setEmail(email);
        }
        if (fullName != null && !fullName.isBlank() && !fullName.equals(user.getFullName())) {
            user.setFullName(fullName);
        }
        return user;
    }

    private User createFromOAuth2(
            AuthProvider provider, String providerSub, String email, String fullName) {
        userRepository
                .findByEmail(email)
                .ifPresent(
                        existing -> {
                            throw ApiException.conflict(
                                    "An account with this email already exists via "
                                            + existing.getAuthProvider()
                                            + ". Sign in with that provider instead.");
                        });

        // Some providers omit the name claim; fall back to the local part of the
        // email rather than persisting a null into a NOT NULL column.
        String resolvedName =
                (fullName == null || fullName.isBlank())
                        ? email.substring(0, email.indexOf('@'))
                        : fullName;

        User created = userRepository.save(new User(email, resolvedName, provider, providerSub));
        log.info("Created user id={} via provider={}", created.getId(), provider);
        return created;
    }

    @Transactional(readOnly = true)
    public User requireById(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> ApiException.notFound("User " + userId + " not found"));
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.from(requireById(userId));
    }

    /**
     * Called by the verification module when an admin approves a request. Sets {@code verified} and
     * grants {@link Role#CREATOR} together — the two are never allowed to drift apart.
     */
    @Transactional
    public void markVerified(Long userId) {
        User user = requireById(userId);
        user.markVerified();
        log.info("User id={} marked verified and granted CREATOR", userId);
    }

    @Transactional(readOnly = true)
    public boolean isVerified(Long userId) {
        return requireById(userId).isVerified();
    }
}
