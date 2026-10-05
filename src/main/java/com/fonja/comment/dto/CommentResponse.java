package com.fonja.comment.dto;

import java.time.Instant;
import java.util.List;

/**
 * One comment as the campaign page shows it. A deleted comment keeps its place in the thread — its
 * replies still make sense under it — but carries no body and no author.
 *
 * @param replies oldest first; always empty on a reply
 * @param canDelete whether the viewer may remove it: its author, the campaign's creator, or an
 *     admin
 */
public record CommentResponse(
        Long id,
        Long parentId,
        String body,
        boolean deleted,
        CommentAuthor author,
        Instant createdAt,
        boolean canDelete,
        List<CommentResponse> replies) {

    /**
     * @param creator the campaign's own creator — shown as a "Creator" badge
     * @param donor has a settled donation to this campaign — shown as a "Donated" badge
     */
    public record CommentAuthor(
            Long id, String name, String avatarUrl, boolean creator, boolean donor) {}
}
