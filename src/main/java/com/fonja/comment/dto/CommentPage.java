package com.fonja.comment.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A page of top-level comments in the same {@code {content, page}} shape every other paged endpoint
 * serializes to, plus the campaign's live comment count with replies included — what the Comments
 * tab shows, since the page's own total counts threads only.
 */
public record CommentPage(List<CommentResponse> content, PageMeta page, long totalCount) {

    public record PageMeta(int size, int number, long totalElements, int totalPages) {}

    public static CommentPage of(Page<CommentResponse> page, long totalCount) {
        return new CommentPage(
                page.getContent(),
                new PageMeta(
                        page.getSize(),
                        page.getNumber(),
                        page.getTotalElements(),
                        page.getTotalPages()),
                totalCount);
    }
}
