package com.fonja.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fonja.campaign.dto.CampaignFeedSort;
import com.fonja.campaign.dto.CampaignResponse;
import com.fonja.campaign.dto.UpdateCampaignRequest;
import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignImage;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.entity.Category;
import com.fonja.campaign.repository.CampaignImageRepository;
import com.fonja.campaign.repository.CampaignRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.donation.dto.CampaignFunding;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import com.fonja.verification.entity.VerificationRequest;
import com.fonja.verification.entity.VerificationStatus;
import com.fonja.verification.entity.VerificationType;
import com.fonja.verification.repository.VerificationRequestRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the rules that live only in the service: the Launch gate, which fundraising terms freeze
 * once a campaign leaves DRAFT, ownership scoping, who may read what, the public feed's ordering,
 * and image ordering.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    private static final Long CREATOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 99L;
    private static final Long CAMPAIGN_ID = 5L;

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignImageRepository campaignImageRepository;
    @Mock private VerificationRequestRepository verificationRequestRepository;
    @Mock private DonationRepository donationRepository;
    @Mock private UserService userService;
    @Mock private CategoryService categoryService;
    @Mock private ApplicationEventPublisher events;

    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        campaignService =
                new CampaignService(
                        campaignRepository,
                        campaignImageRepository,
                        verificationRequestRepository,
                        donationRepository,
                        userService,
                        categoryService,
                        events);
    }

    private User creator(boolean verified) {
        User user = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub-1");
        ReflectionTestUtils.setField(user, "id", CREATOR_ID);
        if (verified) {
            user.markVerified();
        }
        return user;
    }

    private Campaign campaign(User creator, CampaignLifecycleState state) {
        Campaign campaign =
                new Campaign(
                        creator,
                        "Title",
                        "Story",
                        new Category("medical-health", "Medical & Health", null, 120),
                        null,
                        BeneficiaryType.SELF,
                        "Nigeria",
                        null,
                        BigDecimal.valueOf(500_000),
                        "NGN",
                        false,
                        "cover.jpg",
                        null);
        ReflectionTestUtils.setField(campaign, "id", CAMPAIGN_ID);
        ReflectionTestUtils.setField(campaign, "lifecycleState", state);
        return campaign;
    }

    private void givenCampaign(Campaign campaign) {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
    }

    private void givenNoImages() {
        when(campaignImageRepository.findByCampaignIdOrderBySortOrderAsc(CAMPAIGN_ID))
                .thenReturn(List.of());
    }

    private UpdateCampaignRequest update(String title, BigDecimal goalAmount, String currency) {
        return new UpdateCampaignRequest(
                title, null, null, null, null, null, null, goalAmount, currency, null, null, null);
    }

    // --- Launch gate -----------------------------------------------------------------

    @Test
    void verifiedCreatorCanSubmitForReview() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);
        givenNoImages();

        CampaignResponse response = campaignService.submitForReview(CREATOR_ID, CAMPAIGN_ID);

        assertThat(response.getLifecycleState()).isEqualTo(CampaignLifecycleState.PENDING_REVIEW);
        assertThat(campaign.getSubmittedAt()).isNotNull();
    }

    @Test
    void unverifiedCreatorWithVerificationInFlightCanSubmitForReview() {
        User unverified = creator(false);
        Campaign campaign = campaign(unverified, CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);
        givenNoImages();
        when(verificationRequestRepository.findByUserIdAndStatus(
                        CREATOR_ID, VerificationStatus.PENDING))
                .thenReturn(
                        Optional.of(
                                new VerificationRequest(
                                        unverified, VerificationType.INDIVIDUAL, null)));

        CampaignResponse response = campaignService.submitForReview(CREATOR_ID, CAMPAIGN_ID);

        assertThat(response.getLifecycleState()).isEqualTo(CampaignLifecycleState.PENDING_REVIEW);
    }

    @Test
    void unverifiedCreatorWithNoVerificationCannotSubmitForReview() {
        Campaign campaign = campaign(creator(false), CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);
        when(verificationRequestRepository.findByUserIdAndStatus(
                        CREATOR_ID, VerificationStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> campaignService.submitForReview(CREATOR_ID, CAMPAIGN_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VERIFICATION_REQUIRED);

        assertThat(campaign.getLifecycleState()).isEqualTo(CampaignLifecycleState.DRAFT);
    }

    /**
     * The in-flight gate lets a campaign queue for review, but approval still requires a verified
     * creator — otherwise an unapproved submission would be enough to go live.
     */
    @Test
    void approvingAnUnverifiedCreatorsCampaignAutoRejectsIt() {
        Campaign campaign = campaign(creator(false), CampaignLifecycleState.PENDING_REVIEW);
        givenCampaign(campaign);
        givenNoImages();

        CampaignResponse response =
                campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, true, null);

        assertThat(response.getLifecycleState()).isEqualTo(CampaignLifecycleState.DRAFT);
        assertThat(response.getRejectionReason()).isEqualTo("Creator is not a verified creator");
    }

    @Test
    void resubmittingClearsThePreviousRejectionReason() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.PENDING_REVIEW);
        givenCampaign(campaign);
        givenNoImages();
        campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, false, "Needs better evidence");
        assertThat(campaign.getRejectionReason()).isEqualTo("Needs better evidence");

        CampaignResponse response = campaignService.submitForReview(CREATOR_ID, CAMPAIGN_ID);

        assertThat(response.getRejectionReason()).isNull();
    }

    // --- Editing rules ---------------------------------------------------------------

    @Test
    void goalCannotBeChangedOnceTheCampaignLeavesDraft() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        givenCampaign(campaign);

        assertThatThrownBy(
                        () ->
                                campaignService.updateCampaign(
                                        CREATOR_ID,
                                        CAMPAIGN_ID,
                                        update(null, BigDecimal.valueOf(999_999), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);

        assertThat(campaign.getGoalAmount()).isEqualByComparingTo(BigDecimal.valueOf(500_000));
    }

    @Test
    void currencyCannotBeChangedOnceTheCampaignLeavesDraft() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.PENDING_REVIEW);
        givenCampaign(campaign);

        assertThatThrownBy(
                        () ->
                                campaignService.updateCampaign(
                                        CREATOR_ID, CAMPAIGN_ID, update(null, null, "USD")))
                .isInstanceOf(ApiException.class);

        assertThat(campaign.getCurrency()).isEqualTo("NGN");
    }

    @Test
    void goalCanBeChangedWhileStillADraft() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);
        givenNoImages();

        campaignService.updateCampaign(
                CREATOR_ID, CAMPAIGN_ID, update(null, BigDecimal.valueOf(999_999), null));

        assertThat(campaign.getGoalAmount()).isEqualByComparingTo(BigDecimal.valueOf(999_999));
    }

    @Test
    void contentCanStillBeEditedOnALiveCampaign() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        givenCampaign(campaign);
        givenNoImages();

        campaignService.updateCampaign(CREATOR_ID, CAMPAIGN_ID, update("New title", null, null));

        assertThat(campaign.getTitle()).isEqualTo("New title");
    }

    @Test
    void aCampaignThatHasEndedCannotBeEdited() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.COMPLETED));

        assertThatThrownBy(
                        () ->
                                campaignService.updateCampaign(
                                        CREATOR_ID, CAMPAIGN_ID, update("New title", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }

    // --- Ownership and mapping -------------------------------------------------------

    @Test
    void anotherUsersDraftReadsAsNotFound() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.DRAFT));

        assertThatThrownBy(() -> campaignService.getCampaignById(OTHER_USER_ID, CAMPAIGN_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
    }

    @Test
    void aPendingCampaignIsNotVisibleToAnyoneButItsCreator() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.PENDING_REVIEW));

        assertThatThrownBy(() -> campaignService.getCampaignById(null, CAMPAIGN_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
    }

    @Test
    void anActiveCampaignIsReadableAnonymously() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.ACTIVE));
        givenNoImages();

        CampaignResponse response = campaignService.getCampaignById(null, CAMPAIGN_ID);

        assertThat(response.getId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(response.getCreatorName()).isEqualTo("A B");
    }

    @Test
    void anEndedCampaignStaysReadableByAnyone() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.COMPLETED));
        givenNoImages();

        assertThat(campaignService.getCampaignById(OTHER_USER_ID, CAMPAIGN_ID).getLifecycleState())
                .isEqualTo(CampaignLifecycleState.COMPLETED);
    }

    @Test
    void aCancelledCampaignIsNotFoundEvenForItsCreator() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.CANCELLED));

        assertThatThrownBy(() -> campaignService.getCampaignById(CREATOR_ID, CAMPAIGN_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
    }

    @Test
    void creatorStillSeesTheirOwnDraft() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.DRAFT));
        givenNoImages();

        assertThat(campaignService.getCampaignById(CREATOR_ID, CAMPAIGN_ID).getLifecycleState())
                .isEqualTo(CampaignLifecycleState.DRAFT);
    }

    // --- Review decisions notify the creator -----------------------------------------------

    @Test
    void approvalEmailsTheCreator() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.PENDING_REVIEW));
        givenNoImages();

        campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, true, null);

        NotificationEvent.CampaignReviewed event =
                capturePublished(NotificationEvent.CampaignReviewed.class);
        assertThat(event.approved()).isTrue();
        assertThat(event.recipient().userId()).isEqualTo(CREATOR_ID);
        assertThat(event.campaignTitle()).isEqualTo("Title");
    }

    @Test
    void rejectionEmailsTheCreatorWithTheReason() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.PENDING_REVIEW));
        givenNoImages();

        campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, false, "Story is too thin");

        NotificationEvent.CampaignReviewed event =
                capturePublished(NotificationEvent.CampaignReviewed.class);
        assertThat(event.approved()).isFalse();
        assertThat(event.reason()).isEqualTo("Story is too thin");
    }

    @Test
    void autoRejectionEmailsTheBlockerAsTheReason() {
        givenCampaign(campaign(creator(false), CampaignLifecycleState.PENDING_REVIEW));
        givenNoImages();

        campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, true, null);

        NotificationEvent.CampaignReviewed event =
                capturePublished(NotificationEvent.CampaignReviewed.class);
        assertThat(event.approved()).isFalse();
        assertThat(event.reason()).isEqualTo("Creator is not a verified creator");
    }

    // --- Goal reached -> COMPLETED ---------------------------------------------------------

    @Test
    void completesAnActiveCampaignOnceSettledDonationsCoverTheGoal() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        givenCampaign(campaign);
        givenFunding(BigDecimal.valueOf(500_000));

        assertThat(campaignService.completeIfGoalReached(CAMPAIGN_ID)).isTrue();

        assertThat(campaign.getLifecycleState()).isEqualTo(CampaignLifecycleState.COMPLETED);
        NotificationEvent.CampaignCompleted event =
                capturePublished(NotificationEvent.CampaignCompleted.class);
        assertThat(event.raisedAmount()).isEqualByComparingTo("500000");
        assertThat(event.goalAmount()).isEqualByComparingTo("500000");
        assertThat(event.recipient().userId()).isEqualTo(CREATOR_ID);
    }

    @Test
    void leavesACampaignActiveWhileShortOfTheGoal() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        givenCampaign(campaign);
        givenFunding(new BigDecimal("499999.99"));

        assertThat(campaignService.completeIfGoalReached(CAMPAIGN_ID)).isFalse();

        assertThat(campaign.getLifecycleState()).isEqualTo(CampaignLifecycleState.ACTIVE);
        verifyNoInteractions(events);
    }

    /**
     * A late-settling donation to an already-completed campaign must not blow up on the transition.
     */
    @Test
    void completionIsANoOpOnACampaignThatIsNotActive() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.COMPLETED);
        givenCampaign(campaign);

        assertThat(campaignService.completeIfGoalReached(CAMPAIGN_ID)).isFalse();

        verifyNoInteractions(donationRepository, events);
    }

    private void givenFunding(BigDecimal raised) {
        when(donationRepository.fundingByCampaignIds(List.of(CAMPAIGN_ID), DonationStatus.SUCCESS))
                .thenReturn(List.of(new CampaignFunding(CAMPAIGN_ID, raised, 1)));
    }

    private <T> T capturePublished(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        return type.cast(captor.getValue());
    }

    // --- Funding on the response ---------------------------------------------------------

    @Test
    void responseCarriesSettledTotalsFromTheDonationTable() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.ACTIVE));
        givenNoImages();
        when(donationRepository.fundingByCampaignIds(List.of(CAMPAIGN_ID), DonationStatus.SUCCESS))
                .thenReturn(
                        List.of(new CampaignFunding(CAMPAIGN_ID, new BigDecimal("15000.00"), 3)));

        CampaignResponse response = campaignService.getCampaignById(null, CAMPAIGN_ID);

        assertThat(response.getRaisedAmount()).isEqualByComparingTo("15000.00");
        assertThat(response.getDonationCount()).isEqualTo(3);
    }

    @Test
    void aCampaignWithNoDonationsReportsZeroNotNull() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.ACTIVE));
        givenNoImages();

        CampaignResponse response = campaignService.getCampaignById(null, CAMPAIGN_ID);

        assertThat(response.getRaisedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getDonationCount()).isZero();
    }

    // --- Public feed -----------------------------------------------------------------------

    @Test
    void feedDefaultsToNewestApprovedFirstAndOnlyActiveCampaigns() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(campaignRepository.findPublicFeed(any(), any(), any(), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of(campaign)));

        campaignService.publicFeed(null, null, CampaignFeedSort.NEWEST, PageRequest.of(0, 20));

        verify(campaignRepository)
                .findPublicFeed(eq(CampaignLifecycleState.ACTIVE), isNull(), isNull(), any());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("approvedAt"), Sort.Order.desc("id")));
    }

    @Test
    void feedEndingSoonPutsOpenEndedCampaignsLast() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(campaignRepository.findPublicFeed(any(), any(), any(), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        campaignService.publicFeed(null, null, CampaignFeedSort.ENDING_SOON, PageRequest.of(0, 20));

        Sort.Order first = pageable.getValue().getSort().iterator().next();
        assertThat(first.getProperty()).isEqualTo("deadline");
        assertThat(first.isAscending()).isTrue();
        assertThat(first.getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_LAST);
    }

    @Test
    void feedMostRaisedUsesTheAggregateQueryWithoutAClientSort() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(campaignRepository.findPublicFeedByMostRaised(any(), any(), any(), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        campaignService.publicFeed(
                " Medical-Health ",
                "  lagos ",
                CampaignFeedSort.MOST_RAISED,
                PageRequest.of(2, 10, Sort.by("title")));

        verify(campaignRepository)
                .findPublicFeedByMostRaised(
                        eq(CampaignLifecycleState.ACTIVE),
                        eq("medical-health"),
                        eq("lagos"),
                        any());
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        verify(campaignRepository, never()).findPublicFeed(any(), any(), any(), any());
    }

    @Test
    void feedTreatsBlankSearchAsNoSearch() {
        when(campaignRepository.findPublicFeed(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        campaignService.publicFeed(null, "   ", CampaignFeedSort.NEWEST, PageRequest.of(0, 20));

        verify(campaignRepository).findPublicFeed(any(), isNull(), isNull(), any());
    }

    @Test
    void feedPageMapsFundingPerCampaignWithOneQuery() {
        Campaign funded = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        Campaign unfunded = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        ReflectionTestUtils.setField(unfunded, "id", 6L);
        when(campaignRepository.findPublicFeed(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(funded, unfunded)));
        when(campaignImageRepository.findByCampaignIdInOrderByCampaignIdAscSortOrderAsc(
                        List.of(CAMPAIGN_ID, 6L)))
                .thenReturn(List.of());
        when(donationRepository.fundingByCampaignIds(
                        List.of(CAMPAIGN_ID, 6L), DonationStatus.SUCCESS))
                .thenReturn(
                        List.of(new CampaignFunding(CAMPAIGN_ID, new BigDecimal("2500.00"), 1)));

        List<CampaignResponse> page =
                campaignService
                        .publicFeed(null, null, CampaignFeedSort.NEWEST, PageRequest.of(0, 20))
                        .getContent();

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getRaisedAmount()).isEqualByComparingTo("2500.00");
        assertThat(page.get(0).getDonationCount()).isEqualTo(1);
        assertThat(page.get(1).getRaisedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(page.get(1).getDonationCount()).isZero();
        verify(donationRepository).fundingByCampaignIds(any(), any());
    }

    /** creatorId comes off the entity, not the caller — an admin read must not claim ownership. */
    @Test
    void reviewMapsTheCreatorIdNotTheActingAdminId() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.PENDING_REVIEW));
        givenNoImages();

        CampaignResponse response =
                campaignService.reviewCampaign(ADMIN_ID, CAMPAIGN_ID, true, null);

        assertThat(response.getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(response.getLifecycleState()).isEqualTo(CampaignLifecycleState.ACTIVE);
    }

    // --- Images ----------------------------------------------------------------------

    @Test
    void newImageTakesTheNextSortOrderAfterTheHighestInUse() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.DRAFT));
        givenNoImages();
        when(campaignImageRepository.findMaxSortOrder(CAMPAIGN_ID)).thenReturn(4);

        campaignService.addCampaignImage(CREATOR_ID, CAMPAIGN_ID, "later.jpg");

        ArgumentCaptor<CampaignImage> saved = ArgumentCaptor.forClass(CampaignImage.class);
        verify(campaignImageRepository).save(saved.capture());
        assertThat(saved.getValue().getSortOrder()).isEqualTo(5);
    }

    @Test
    void firstImageOnACampaignTakesSortOrderZero() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.DRAFT));
        givenNoImages();
        when(campaignImageRepository.findMaxSortOrder(CAMPAIGN_ID)).thenReturn(-1);

        campaignService.addCampaignImage(CREATOR_ID, CAMPAIGN_ID, "first.jpg");

        ArgumentCaptor<CampaignImage> saved = ArgumentCaptor.forClass(CampaignImage.class);
        verify(campaignImageRepository).save(saved.capture());
        assertThat(saved.getValue().getSortOrder()).isZero();
    }

    // --- Deletion --------------------------------------------------------------------

    @Test
    void deletingADraftRemovesItOutright() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);

        campaignService.deleteCampaign(CREATOR_ID, CAMPAIGN_ID);

        verify(campaignRepository).delete(campaign);
    }

    @Test
    void deletingALiveCampaignCancelsItInstead() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.ACTIVE);
        givenCampaign(campaign);

        campaignService.deleteCampaign(CREATOR_ID, CAMPAIGN_ID);

        verify(campaignRepository, never()).delete(any(Campaign.class));
        assertThat(campaign.getLifecycleState()).isEqualTo(CampaignLifecycleState.CANCELLED);
    }

    @Test
    void deletingAnAlreadyEndedCampaignConflicts() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.EXPIRED));

        assertThatThrownBy(() -> campaignService.deleteCampaign(CREATOR_ID, CAMPAIGN_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }

    // --- Categories ---------------------------------------------------------------

    private UpdateCampaignRequest recategorize(String category, String note) {
        return new UpdateCampaignRequest(
                null, null, category, note, null, null, null, null, null, null, null, null);
    }

    @Test
    void aNoteIsKeptWhenMovingIntoOthers() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);
        givenNoImages();
        when(categoryService.requireAssignable("others"))
                .thenReturn(new Category("others", "Others", null, 9999));

        campaignService.updateCampaign(
                CREATOR_ID, CAMPAIGN_ID, recategorize("others", "  Pet rescue "));

        assertThat(campaign.getCategory().getSlug()).isEqualTo("others");
        assertThat(campaign.getCategoryNote()).isEqualTo("Pet rescue");
    }

    @Test
    void aNoteIsRefusedOutsideOthers() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.DRAFT);
        givenCampaign(campaign);

        assertThatThrownBy(
                        () ->
                                campaignService.updateCampaign(
                                        CREATOR_ID, CAMPAIGN_ID, recategorize(null, "Pet rescue")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.VALIDATION_ERROR);
    }

    @Test
    void movingOutOfOthersDropsTheNote() {
        Campaign campaign = campaign(creator(true), CampaignLifecycleState.DRAFT);
        campaign.setCategory(new Category("others", "Others", null, 9999));
        campaign.setCategoryNote("Pet rescue");
        givenCampaign(campaign);
        givenNoImages();
        when(categoryService.requireAssignable("education"))
                .thenReturn(new Category("education", "Education", null, 10));

        campaignService.updateCampaign(CREATOR_ID, CAMPAIGN_ID, recategorize("education", null));

        assertThat(campaign.getCategory().getSlug()).isEqualTo("education");
        assertThat(campaign.getCategoryNote()).isNull();
    }
}
