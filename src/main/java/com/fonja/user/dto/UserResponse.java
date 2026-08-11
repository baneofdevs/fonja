package com.fonja.user.dto;

import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.Role;
import com.fonja.user.entity.User;

import java.time.Instant;
import java.util.Set;

/**
 * Outbound view of a user. Deliberately excludes {@code authProviderSub} — it
 * is an authentication identifier and has no business being in an API body.
 */
public record UserResponse(
        Long id,
        String email,
        String fullName,
        String phone,
        AuthProvider authProvider,
        Set<Role> roles,
        boolean verified,
        Instant createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getAuthProvider(),
                user.getRoles(),
                user.isVerified(),
                user.getCreatedAt()
        );
    }
}
