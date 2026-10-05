package com.fonja.user.repository;

import com.fonja.user.entity.AuthToken;
import com.fonja.user.entity.AuthTokenPurpose;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    /** The newest token of a kind — the only one a code is checked against. */
    Optional<AuthToken> findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
            Long userId, AuthTokenPurpose purpose);

    Optional<AuthToken> findByTokenHashAndPurpose(String tokenHash, AuthTokenPurpose purpose);

    /** Retires every open token of a kind, so issuing a new one leaves exactly one live. */
    @Modifying
    @Query(
            "update AuthToken t set t.consumedAt = :now"
                    + " where t.user.id = :userId and t.purpose = :purpose and t.consumedAt is null")
    int consumeAllOpen(
            @Param("userId") Long userId,
            @Param("purpose") AuthTokenPurpose purpose,
            @Param("now") Instant now);
}
