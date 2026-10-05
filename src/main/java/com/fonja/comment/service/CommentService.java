package com.fonja.comment.service;

import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.service.CampaignService;
import com.fonja.comment.dto.CommentPage;
import com.fonja.comment.dto.CommentResponse;
import com.fonja.comment.dto.CommentResponse.CommentAuthor;
import com.fonja.comment.dto.CreateCommentRequest;
import com.fonja.comment.entity.CampaignComment;
import com.fonja.comment.repository.CampaignCommentRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.donation.service.DonationService;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments on campaigns: anyone signed in can post on a live or finished campaign, replies go one
 * level deep, and a comment can be removed by its author, the campaign's creator or an admin.
 *
 * <p>Reads follow the campaign's own visibility — {@link CampaignService#requireVisibleCampaign} —
 * so a draft's comments (there cannot be any, but still) are exactly as hidden as the draft.
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    /** What a notification quotes of a comment. */
    private static final int EXCERPT_LENGTH = 140;

    private final CampaignCommentRepository commentRepository;
    private final CampaignService campaignService;
    private final DonationService donationService;
    private final UserService userService;
    private final ApplicationEventPublisher events;

    public CommentService(
            CampaignCommentRepository commentRepository,
            CampaignService campaignService,
            DonationService donationService,
            UserService userService,
            ApplicationEventPublisher events) {
        this.commentRepository = commentRepository;
        this.campaignService = campaignService;
        this.donationService = donationService;
        this.userService = userService;
        this.events = events;
    }

    /**
     * A page of top-level comments, newest first, each with all its replies (oldest first).
     *
     * @param viewerId null for an anonymous visitor
     * @param viewerIsAdmin lets the response say an admin may delete anything
     */
    @Transactional(readOnly = true)
    public CommentPage list(
            Long viewerId, boolean viewerIsAdmin, Long campaignId, Pageable pageable) {
        Campaign campaign = campaignService.requireVisibleCampaign(viewerId, campaignId);
        Long creatorId = campaign.getCreator().getId();

        Page<CampaignComment> threads =
                commentRepository.findByCampaignIdAndParentIdIsNullOrderByCreatedAtDescIdDesc(
                        campaignId, pageable);
        List<Long> threadIds = threads.getContent().stream().map(CampaignComment::getId).toList();
        List<CampaignComment> replies =
                threadIds.isEmpty()
                        ? List.of()
                        : commentRepository.findByParentIdInOrderByCreatedAtAscIdAsc(threadIds);

        Set<Long> authorIds = new HashSet<>();
        threads.forEach(c -> authorIds.add(c.getAuthor().getId()));
        replies.forEach(c -> authorIds.add(c.getAuthor().getId()));
        Set<Long> donors = donationService.donorsAmong(campaignId, authorIds);

        Map<Long, List<CommentResponse>> repliesByParent = new LinkedHashMap<>();
        for (CampaignComment reply : replies) {
            repliesByParent
                    .computeIfAbsent(reply.getParentId(), k -> new ArrayList<>())
                    .add(toResponse(reply, List.of(), creatorId, donors, viewerId, viewerIsAdmin));
        }

        Page<CommentResponse> page =
                threads.map(
                        c ->
                                toResponse(
                                        c,
                                        repliesByParent.getOrDefault(c.getId(), List.of()),
                                        creatorId,
                                        donors,
                                        viewerId,
                                        viewerIsAdmin));
        return CommentPage.of(page, commentRepository.countLive(campaignId));
    }

    /**
     * Posts a comment, or a reply when {@code parentId} is set. Only on a campaign the public can
     * see — a creator cannot seed comments on their own draft — and replies only to a live
     * top-level comment on the same campaign.
     */
    @Transactional
    public CommentResponse post(Long authorId, Long campaignId, CreateCommentRequest request) {
        Campaign campaign = campaignService.requireVisibleCampaign(authorId, campaignId);
        if (!campaign.getLifecycleState().isPubliclyVisible()) {
            throw ApiException.conflict(
                    campaign.getLifecycleState() == CampaignLifecycleState.DRAFT
                            ? "Comments open once the campaign is live"
                            : "Comments are closed on this campaign");
        }
        String body = request.body().trim();
        if (body.isEmpty()) {
            throw ApiException.badRequest("Write something before posting");
        }

        CampaignComment parent = null;
        if (request.parentId() != null) {
            parent =
                    commentRepository
                            .findById(request.parentId())
                            .filter(p -> p.getCampaign().getId().equals(campaignId))
                            .orElseThrow(() -> ApiException.notFound("Comment not found"));
            if (parent.isReply()) {
                throw ApiException.badRequest("Replies can only be made to a top-level comment");
            }
            if (parent.isDeleted()) {
                throw ApiException.conflict("That comment has been removed");
            }
        }

        User author = userService.requireById(authorId);
        CampaignComment saved =
                commentRepository.save(
                        new CampaignComment(
                                campaign, author, parent == null ? null : parent.getId(), body));
        log.info(
                "Comment id={} posted on campaign id={} by user id={}",
                saved.getId(),
                campaignId,
                authorId);

        notifyInterestedParties(campaign, parent, author, saved);

        Long creatorId = campaign.getCreator().getId();
        Set<Long> donors = donationService.donorsAmong(campaignId, Set.of(authorId));
        return toResponse(saved, List.of(), creatorId, donors, authorId, false);
    }

    /**
     * Removes a comment. Soft: its replies keep their place, and the body is withheld from then on.
     * Anyone else asking gets a 404, as though it were not there to delete.
     */
    @Transactional
    public void delete(Long userId, boolean isAdmin, Long campaignId, Long commentId) {
        CampaignComment comment =
                commentRepository
                        .findById(commentId)
                        .filter(c -> c.getCampaign().getId().equals(campaignId))
                        .orElseThrow(() -> ApiException.notFound("Comment not found"));
        if (!mayDelete(comment, comment.getCampaign().getCreator().getId(), userId, isAdmin)) {
            throw ApiException.notFound("Comment not found");
        }
        comment.delete(Instant.now());
        log.info("Comment id={} deleted by user id={}", commentId, userId);
    }

    // --- internals -----------------------------------------------------------------------

    /**
     * The creator hears about every comment on their campaign; the author of a comment hears about
     * replies to it. Nobody is told about their own words, and nobody is told twice.
     */
    private void notifyInterestedParties(
            Campaign campaign, CampaignComment parent, User author, CampaignComment comment) {
        String excerpt = excerpt(comment.getBody());
        Set<Long> notified = new HashSet<>();
        notified.add(author.getId());

        User creator = campaign.getCreator();
        if (notified.add(creator.getId())) {
            events.publishEvent(event(creator, campaign, author, comment, excerpt));
        }
        if (parent != null && notified.add(parent.getAuthor().getId())) {
            events.publishEvent(event(parent.getAuthor(), campaign, author, comment, excerpt));
        }
    }

    private static NotificationEvent.CampaignCommentPosted event(
            User recipient,
            Campaign campaign,
            User author,
            CampaignComment comment,
            String excerpt) {
        return new NotificationEvent.CampaignCommentPosted(
                Recipient.of(recipient),
                campaign.getId(),
                campaign.getTitle(),
                comment.getId(),
                author.getFullName(),
                excerpt);
    }

    static String excerpt(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_LENGTH
                ? flat
                : flat.substring(0, EXCERPT_LENGTH - 1).trim() + "…";
    }

    private static boolean mayDelete(
            CampaignComment comment, Long creatorId, Long viewerId, boolean viewerIsAdmin) {
        if (viewerId == null) {
            return false;
        }
        return viewerIsAdmin
                || comment.getAuthor().getId().equals(viewerId)
                || creatorId.equals(viewerId);
    }

    private static CommentResponse toResponse(
            CampaignComment comment,
            List<CommentResponse> replies,
            Long creatorId,
            Set<Long> donors,
            Long viewerId,
            boolean viewerIsAdmin) {
        if (comment.isDeleted()) {
            return new CommentResponse(
                    comment.getId(),
                    comment.getParentId(),
                    null,
                    true,
                    null,
                    comment.getCreatedAt(),
                    false,
                    replies);
        }
        User author = comment.getAuthor();
        return new CommentResponse(
                comment.getId(),
                comment.getParentId(),
                comment.getBody(),
                false,
                new CommentAuthor(
                        author.getId(),
                        author.getFullName(),
                        UserResponse.avatarUrl(author.getAvatarMediaId()),
                        author.getId().equals(creatorId),
                        donors.contains(author.getId())),
                comment.getCreatedAt(),
                mayDelete(comment, creatorId, viewerId, viewerIsAdmin),
                replies);
    }
}
