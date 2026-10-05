package com.fonja.notification.repository;

import com.fonja.notification.entity.UserNotification;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    Page<UserNotification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Page<UserNotification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
            Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** Scoped by user so one person can never touch another's inbox by guessing an id. */
    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query(
            "update UserNotification n set n.readAt = :now"
                    + " where n.user.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
