package com.fonja.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.entity.Category;
import com.fonja.campaign.service.CampaignService;
import com.fonja.comment.dto.CommentPage;
import com.fonja.comment.dto.CommentResponse;
import com.fonja.comment.dto.CreateCommentRequest;
import com.fonja.comment.entity.CampaignComment;
import com.fonja.comment.repository.CampaignCommentRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.donation.service.DonationService;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    private static final long CAMPAIGN_ID = 5L;
    private static final long CREATOR_ID = 1L;
    private static final long DONOR_ID = 2L;
    private static final long STRANGER_ID = 3L;

    @Mock private CampaignCommentRepository commentRepository;
    @Mock private CampaignService campaignService;
    @Mock private DonationService donationService;
    @Mock private UserService userService;
    @Mock private ApplicationEventPublisher events;

    private CommentService commentService;
    private User creator;
    private User donor;
    private User stranger;
    private Campaign campaign;
    private long nextCommentId = 100;

    @BeforeEach
    void setUp() {
        commentService =
                new CommentService(
                        commentRepository, campaignService, donationService, userService, events);
        creator = user(CREATOR_ID, "Ada Obi");
        donor = user(DONOR_ID, "Tunde Bello");
        stranger = user(STRANGER_ID, "Kemi Ade");
        campaign = campaign(CampaignLifecycleState.ACTIVE);

        lenient()
                .when(campaignService.requireVisibleCampaign(any(), eq(CAMPAIGN_ID)))
                .thenAnswer(inv -> campaign);
        lenient().when(userService.requireById(DONOR_ID)).thenReturn(donor);
        lenient().when(userService.requireById(STRANGER_ID)).thenReturn(stranger);
        lenient().when(userService.requireById(CREATOR_ID)).thenReturn(creator);
        lenient()
                .when(commentRepository.save(any(CampaignComment.class)))
                .thenAnswer(
                        inv -> {
                            CampaignComment c = inv.getArgument(0);
                            ReflectionTestUtils.setField(c, "id", nextCommentId++);
                            ReflectionTestUtils.setField(c, "createdAt", Instant.now());
                            return c;
                        });
        lenient()
                .when(donationService.donorsAmong(eq(CAMPAIGN_ID), any()))
                .thenReturn(Set.of(DONOR_ID));
    }

    private static User user(long id, String name) {
        User user =
                new User(
                        name.toLowerCase().replace(' ', '.') + "@x.ng",
                        name,
                        AuthProvider.GOOGLE,
                        "s" + id);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Campaign campaign(CampaignLifecycleState state) {
        Campaign c =
                new Campaign(
                        creator,
                        "Help Ada",
                        "Story",
                        new Category("education", "Education", null, 10),
                        null,
                        BeneficiaryType.SELF,
                        "Nigeria",
                        null,
                        BigDecimal.valueOf(500_000),
                        "NGN",
                        false,
                        "cover.jpg",
                        null);
        ReflectionTestUtils.setField(c, "id", CAMPAIGN_ID);
        ReflectionTestUtils.setField(c, "lifecycleState", state);
        return c;
    }

    private CampaignComment existing(long id, User author, Long parentId) {
        CampaignComment c = new CampaignComment(campaign, author, parentId, "Comment " + id);
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "createdAt", Instant.now());
        return c;
    }

    // --- posting ------------------------------------------------------------------------

    @Test
    void aCommentIsSavedTrimmedAndTheCreatorIsNotified() {
        CommentResponse response =
                commentService.post(
                        DONOR_ID,
                        CAMPAIGN_ID,
                        new CreateCommentRequest("  Rooting for you!  ", null));

        assertThat(response.body()).isEqualTo("Rooting for you!");
        assertThat(response.author().donor()).isTrue();
        assertThat(response.author().creator()).isFalse();
        assertThat(response.canDelete()).isTrue();

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).publishEvent(event.capture());
        NotificationEvent.CampaignCommentPosted posted =
                (NotificationEvent.CampaignCommentPosted) event.getValue();
        assertThat(posted.recipient().userId()).isEqualTo(CREATOR_ID);
        assertThat(posted.commenterName()).isEqualTo("Tunde Bello");
    }

    @Test
    void theCreatorCommentingOnTheirOwnCampaignNotifiesNobody() {
        commentService.post(
                CREATOR_ID, CAMPAIGN_ID, new CreateCommentRequest("Thank you all", null));

        verify(events, never()).publishEvent(any(NotificationEvent.class));
    }

    @Test
    void aReplyNotifiesTheCreatorAndTheParentAuthorOnceEach() {
        CampaignComment parent = existing(50, donor, null);
        when(commentRepository.findById(50L)).thenReturn(Optional.of(parent));

        commentService.post(STRANGER_ID, CAMPAIGN_ID, new CreateCommentRequest("Same here", 50L));

        verify(events, times(2)).publishEvent(any(NotificationEvent.class));
    }

    @Test
    void theCreatorReplyingToADonorNotifiesOnlyTheDonor() {
        CampaignComment parent = existing(50, donor, null);
        when(commentRepository.findById(50L)).thenReturn(Optional.of(parent));

        commentService.post(CREATOR_ID, CAMPAIGN_ID, new CreateCommentRequest("Thank you!", 50L));

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().recipient().userId()).isEqualTo(DONOR_ID);
    }

    @Test
    void repliesGoOnlyOneLevelDeep() {
        CampaignComment reply = existing(51, donor, 50L);
        when(commentRepository.findById(51L)).thenReturn(Optional.of(reply));

        assertThatThrownBy(
                        () ->
                                commentService.post(
                                        STRANGER_ID,
                                        CAMPAIGN_ID,
                                        new CreateCommentRequest("Deeper", 51L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void aReplyToACommentOnAnotherCampaignIsNotFound() {
        CampaignComment elsewhere = existing(60, donor, null);
        Campaign other = campaign(CampaignLifecycleState.ACTIVE);
        ReflectionTestUtils.setField(other, "id", 99L);
        ReflectionTestUtils.setField(elsewhere, "campaign", other);
        when(commentRepository.findById(60L)).thenReturn(Optional.of(elsewhere));

        assertThatThrownBy(
                        () ->
                                commentService.post(
                                        STRANGER_ID,
                                        CAMPAIGN_ID,
                                        new CreateCommentRequest("Hi", 60L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
    }

    @Test
    void aCreatorCannotSeedCommentsOnTheirOwnDraft() {
        campaign = campaign(CampaignLifecycleState.DRAFT);

        assertThatThrownBy(
                        () ->
                                commentService.post(
                                        CREATOR_ID,
                                        CAMPAIGN_ID,
                                        new CreateCommentRequest("First!", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("once the campaign is live");
    }

    // --- deleting ----------------------------------------------------------------------

    @Test
    void theAuthorTheCreatorAndAnAdminCanDelete() {
        for (long deleter : new long[] {DONOR_ID, CREATOR_ID, 42L}) {
            CampaignComment comment = existing(70, donor, null);
            when(commentRepository.findById(70L)).thenReturn(Optional.of(comment));

            commentService.delete(deleter, deleter == 42L, CAMPAIGN_ID, 70L);

            assertThat(comment.isDeleted()).isTrue();
        }
    }

    @Test
    void anyoneElseDeletingGetsNotFound() {
        CampaignComment comment = existing(70, donor, null);
        when(commentRepository.findById(70L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.delete(STRANGER_ID, false, CAMPAIGN_ID, 70L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
        assertThat(comment.isDeleted()).isFalse();
    }

    // --- reading -----------------------------------------------------------------------

    @Test
    void aPageCarriesItsRepliesBadgesAndHidesDeletedBodies() {
        CampaignComment thread = existing(80, donor, null);
        CampaignComment removed = existing(81, stranger, null);
        removed.delete(Instant.now());
        CampaignComment creatorReply = existing(82, creator, 80L);
        when(commentRepository.findByCampaignIdAndParentIdIsNullOrderByCreatedAtDescIdDesc(
                        eq(CAMPAIGN_ID), any()))
                .thenReturn(new PageImpl<>(List.of(thread, removed), PageRequest.of(0, 10), 2));
        when(commentRepository.findByParentIdInOrderByCreatedAtAscIdAsc(List.of(80L, 81L)))
                .thenReturn(List.of(creatorReply));
        when(commentRepository.countLive(CAMPAIGN_ID)).thenReturn(2L);

        CommentPage page = commentService.list(null, false, CAMPAIGN_ID, PageRequest.of(0, 10));

        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.page().totalElements()).isEqualTo(2);
        CommentResponse first = page.content().get(0);
        assertThat(first.author().donor()).isTrue();
        assertThat(first.canDelete()).isFalse();
        assertThat(first.replies())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.author().creator()).isTrue();
                            assertThat(r.parentId()).isEqualTo(80L);
                        });
        CommentResponse second = page.content().get(1);
        assertThat(second.deleted()).isTrue();
        assertThat(second.body()).isNull();
        assertThat(second.author()).isNull();
    }

    @Test
    void anEmptyPageDoesNotAskForRepliesOrDonors() {
        when(commentRepository.findByCampaignIdAndParentIdIsNullOrderByCreatedAtDescIdDesc(
                        anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        commentService.list(STRANGER_ID, false, CAMPAIGN_ID, PageRequest.of(0, 10));

        verify(commentRepository, never()).findByParentIdInOrderByCreatedAtAscIdAsc(any());
    }

    @Test
    void longCommentsAreShortenedForNotifications() {
        String excerpt = CommentService.excerpt("word ".repeat(100));
        assertThat(excerpt).hasSizeLessThanOrEqualTo(140).endsWith("…");
        assertThat(CommentService.excerpt("line one\n\nline   two")).isEqualTo("line one line two");
    }
}
