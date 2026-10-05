package com.fonja.notification.event;

import com.fonja.user.entity.User;

/**
 * Who a notification goes to, captured as plain values at the moment the event is published.
 *
 * <p>Deliberately not the {@link User} entity. The listener runs on another thread after the
 * publishing transaction has committed, and an entity — or a lazy proxy of one, which is what
 * {@code campaign.getCreator()} usually is — cannot be read there.
 */
public record Recipient(Long userId, String email, String name) {

    public static Recipient of(User user) {
        return new Recipient(user.getId(), user.getEmail(), user.getFullName());
    }
}
