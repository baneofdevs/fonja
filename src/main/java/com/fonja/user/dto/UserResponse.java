package com.fonja.user.dto;

import com.fonja.campaign.dto.CategoryRef;
import com.fonja.campaign.entity.Category;
import com.fonja.user.entity.AccountIntent;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.Role;
import com.fonja.user.entity.User;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Outbound view of a user. Deliberately excludes {@code authProviderSub} and the password hash —
 * authentication material has no business being in an API body; {@code hasPassword} says only
 * whether one is set.
 */
public record UserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String fullName,
        String phone,
        AuthProvider authProvider,
        Set<Role> roles,
        boolean verified,
        boolean emailVerified,
        boolean hasPassword,
        AccountIntent accountIntent,
        boolean onboarded,
        String avatarUrl,
        String bio,
        String location,
        List<CategoryRef> interests,
        Instant createdAt) {

    /** Where avatars are served; the same public media route campaign images use. */
    public static final String MEDIA_PATH = "/api/v1/media/";

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getFullName(),
                user.getPhone(),
                user.getAuthProvider(),
                user.getRoles(),
                user.isVerified(),
                user.isEmailVerified(),
                user.hasPassword(),
                user.getAccountIntent(),
                user.isOnboarded(),
                avatarUrl(user.getAvatarMediaId()),
                user.getBio(),
                user.getLocation(),
                user.getInterests().stream()
                        .sorted(
                                Comparator.comparingInt(Category::getSortOrder)
                                        .thenComparing(Category::getName))
                        .map(CategoryRef::from)
                        .toList(),
                user.getCreatedAt());
    }

    public static String avatarUrl(Long avatarMediaId) {
        return avatarMediaId == null ? null : MEDIA_PATH + avatarMediaId;
    }
}
