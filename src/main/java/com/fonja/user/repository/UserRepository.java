package com.fonja.user.repository;

import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * Row-level lock on a user, held to the end of the surrounding transaction.
     *
     * <p>Exists for the ledger's withdrawal path: two withdrawal requests arriving at once would
     * otherwise both read the same available balance and both be allowed, letting a creator take
     * out more than they have. Locking the user row serialises them. Nothing else should need this
     * — a lock taken casually is a deadlock waiting for a second caller that takes it in another
     * order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(Long id);

    /**
     * The provider `sub` is the stable identifier — an email can change on the provider side, a
     * `sub` cannot. Scoped by provider because two providers can independently issue the same `sub`
     * string.
     */
    Optional<User> findByAuthProviderAndAuthProviderSub(
            AuthProvider authProvider, String authProviderSub);
}
