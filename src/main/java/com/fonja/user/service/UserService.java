package com.fonja.user.service;

import com.fonja.campaign.service.CategoryService;
import com.fonja.common.exception.ApiException;
import com.fonja.common.util.PhoneNumbers;
import com.fonja.user.dto.ChangePasswordRequest;
import com.fonja.user.dto.OnboardingRequest;
import com.fonja.user.dto.UpdateProfileRequest;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.Role;
import com.fonja.user.entity.User;
import com.fonja.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only entry point into the {@code user} module. Other modules call this, never {@link
 * UserRepository} or {@link User} directly.
 *
 * <p>Anything returning a {@link UserResponse} builds it here, inside the transaction: the response
 * reads the user's interests, a lazy collection, and {@code open-in-view} is off.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final CategoryService categoryService;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            CategoryService categoryService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.categoryService = categoryService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Resolves an OAuth2 login to a Fonja user, creating the account on first sign-in. Matching is
     * on the provider `sub` first; a user who signed up with the same email under a different
     * provider is a conflict rather than a silent account merge — linking identities is a
     * deliberate feature, not something to fall into by accident.
     *
     * @param givenName the provider's first-name claim, when it sends one
     * @param familyName the provider's last-name claim, when it sends one
     * @param fullName the display-name claim, split at the first space when the parts are missing
     */
    @Transactional
    public User upsertFromOAuth2(
            AuthProvider provider,
            String providerSub,
            String email,
            String givenName,
            String familyName,
            String fullName) {
        if (providerSub == null || providerSub.isBlank()) {
            throw ApiException.badRequest("OAuth2 provider returned no subject identifier");
        }
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("OAuth2 provider returned no email address");
        }
        String[] name = resolveName(email, givenName, familyName, fullName);

        return userRepository
                .findByAuthProviderAndAuthProviderSub(provider, providerSub)
                .map(existing -> refreshEmail(existing, email))
                .orElseGet(() -> createFromOAuth2(provider, providerSub, email, name));
    }

    /**
     * The provider is authoritative on the email. The name is not refreshed: once someone has an
     * account they can edit their name in Fonja, and a login must not quietly undo that.
     */
    private User refreshEmail(User user, String email) {
        if (!email.equals(user.getEmail())) {
            user.setEmail(email);
        }
        return user;
    }

    private User createFromOAuth2(
            AuthProvider provider, String providerSub, String email, String[] name) {
        userRepository
                .findByEmail(email)
                .ifPresent(
                        existing -> {
                            throw ApiException.conflict(
                                    "An account with this email already exists. Log in with your"
                                            + " email and password instead.");
                        });

        User created =
                userRepository.save(new User(email, name[0], name[1], provider, providerSub));
        log.info("Created user id={} via provider={}", created.getId(), provider);
        return created;
    }

    /**
     * First and last name from whatever the provider sent. Some providers omit every name claim;
     * the local part of the email stands in rather than a null in a NOT NULL column.
     */
    private static String[] resolveName(
            String email, String givenName, String familyName, String fullName) {
        if (givenName != null && !givenName.isBlank()) {
            return new String[] {givenName.trim(), familyName == null ? "" : familyName.trim()};
        }
        String display =
                fullName == null || fullName.isBlank()
                        ? email.substring(0, email.indexOf('@'))
                        : fullName.trim();
        int space = display.indexOf(' ');
        return space < 0
                ? new String[] {display, ""}
                : new String[] {display.substring(0, space), display.substring(space + 1).trim()};
    }

    @Transactional(readOnly = true)
    public User requireById(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> ApiException.notFound("User " + userId + " not found"));
    }

    /**
     * As {@link #requireById}, but takes a row lock held until the caller's transaction ends.
     *
     * <p>For the ledger's withdrawal path only — see {@link UserRepository#findByIdForUpdate}. Must
     * be called inside an existing transaction; on its own the lock is released immediately and
     * buys nothing.
     */
    @Transactional
    public User requireByIdForUpdate(Long userId) {
        return userRepository
                .findByIdForUpdate(userId)
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

    // --- profile -----------------------------------------------------------------------

    /** The last two sign-up steps. Can be repeated; the first completion time is kept. */
    @Transactional
    public UserResponse completeOnboarding(Long userId, OnboardingRequest request) {
        User user = requireById(userId);
        user.setAccountIntent(request.accountIntent());
        user.replaceInterests(
                categoryService.requireAssignable(
                        request.interests() == null ? List.of() : request.interests()));
        user.markOnboarded(Instant.now());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireById(userId);
        if (request.firstName() != null || request.lastName() != null) {
            String first =
                    request.firstName() == null ? user.getFirstName() : request.firstName().trim();
            String last =
                    request.lastName() == null ? user.getLastName() : request.lastName().trim();
            if (first.isEmpty()) {
                throw ApiException.badRequest("firstName cannot be blank");
            }
            user.setName(first, last);
        }
        if (request.phone() != null) {
            user.setPhone(
                    request.phone().isBlank()
                            ? null
                            : PhoneNumbers.normalizeNigerian(request.phone()));
        }
        if (request.bio() != null) {
            user.setBio(blankToNull(request.bio()));
        }
        if (request.location() != null) {
            user.setLocation(blankToNull(request.location()));
        }
        if (request.accountIntent() != null) {
            user.setAccountIntent(request.accountIntent());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse replaceInterests(Long userId, List<String> interests) {
        User user = requireById(userId);
        user.replaceInterests(categoryService.requireAssignable(interests));
        return UserResponse.from(user);
    }

    /**
     * Points the avatar at an image the caller has just uploaded through the media service (the
     * controller does both, so ownership is never in question). Null removes the avatar.
     */
    @Transactional
    public UserResponse setAvatar(Long userId, Long mediaId) {
        User user = requireById(userId);
        user.setAvatarMediaId(mediaId);
        return UserResponse.from(user);
    }

    /**
     * Changes the password, or sets a first one on an OAuth account. The current password is
     * required whenever one exists, so a stolen session alone cannot lock the owner out.
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requireById(userId);
        if (user.hasPassword()
                && (request.currentPassword() == null
                        || !passwordEncoder.matches(
                                request.currentPassword(), user.getPasswordHash()))) {
            throw ApiException.badRequest("Your current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        log.info("User id={} changed their password", userId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
