package com.fonja.notification.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.notification.dto.NotificationResponse;
import com.fonja.notification.dto.UnreadCountResponse;
import com.fonja.notification.service.InAppNotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** The signed-in user's own inbox. Every route is scoped to the token's user. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 50;

    private final InAppNotificationService notifications;

    public NotificationController(InAppNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public CustomResponse<Page<NotificationResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return CustomResponse.success(
                notifications.list(
                        JwtService.userIdFrom(jwt),
                        unreadOnly,
                        PageRequest.of(
                                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    /** What the bell polls; deliberately tiny. */
    @GetMapping("/unread-count")
    public CustomResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(
                new UnreadCountResponse(notifications.unreadCount(JwtService.userIdFrom(jwt))));
    }

    @PostMapping("/{notificationId}/read")
    public CustomResponse<NotificationResponse> markRead(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long notificationId) {
        return CustomResponse.success(
                notifications.markRead(JwtService.userIdFrom(jwt), notificationId));
    }

    @PostMapping("/read-all")
    public CustomResponse<UnreadCountResponse> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(JwtService.userIdFrom(jwt));
        return CustomResponse.success(new UnreadCountResponse(0));
    }
}
