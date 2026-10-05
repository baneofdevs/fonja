package com.fonja.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code parentId} set makes this a reply to that top-level comment. */
public record CreateCommentRequest(
        @NotBlank(message = "Write something before posting")
                @Size(max = 1000, message = "Comments can be at most 1000 characters")
                String body,
        Long parentId) {}
