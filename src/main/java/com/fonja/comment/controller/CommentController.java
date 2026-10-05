package com.fonja.comment.controller;

import com.fonja.comment.dto.CommentPage;
import com.fonja.comment.dto.CommentResponse;
import com.fonja.comment.dto.CreateCommentRequest;
import com.fonja.comment.service.CommentService;
import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import jakarta.validation.Valid;
import java.util.Collection;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * A campaign's comments. Reading is public, like the campaign page itself — see {@code
 * SecurityConfig}; a token sent anyway is decoded, so the response can say which comments the
 * viewer may delete. Posting and deleting need a token.
 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/comments")
public class CommentController {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public CustomResponse<CommentPage> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return CustomResponse.success(
                commentService.list(
                        JwtService.userIdFromOrNull(jwt),
                        isAdmin(jwt),
                        campaignId,
                        PageRequest.of(
                                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    @PostMapping
    public CustomResponse<CommentResponse> post(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateCommentRequest request) {
        return CustomResponse.success(
                "Comment posted",
                commentService.post(JwtService.userIdFrom(jwt), campaignId, request));
    }

    @DeleteMapping("/{commentId}")
    public CustomResponse<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long campaignId,
            @PathVariable Long commentId) {
        commentService.delete(JwtService.userIdFrom(jwt), isAdmin(jwt), campaignId, commentId);
        return CustomResponse.success("Comment removed", null);
    }

    private static boolean isAdmin(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        Object roles = jwt.getClaim(JwtService.ROLES_CLAIM);
        return roles instanceof Collection<?> c && c.contains("ADMIN");
    }
}
