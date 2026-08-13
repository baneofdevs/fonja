package com.fonja.user.repository;

import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * The provider `sub` is the stable identifier — an email can change on the provider side, a
     * `sub` cannot. Scoped by provider because two providers can independently issue the same `sub`
     * string.
     */
    Optional<User> findByAuthProviderAndAuthProviderSub(
            AuthProvider authProvider, String authProviderSub);
}
