package com.fonja.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.campaign.dto.CampaignResponse;
import com.fonja.campaign.dto.UpdateCampaignRequest;
import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.campaign.entity.CampaignImage;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.repository.CampaignImageRepository;
import com.fonja.campaign.repository.CampaignRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the rules that live only in the service: the Launch gate, which fundraising terms freeze
 * once a campaign leaves DRAFT, ownership scoping, and image ordering.
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
    @Mock private UserService userService;

    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        campaignService =
                new CampaignService(
                        campaignRepository,
                        campaignImageRepository,
                        verificationRequestRepository,
                        userService);
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
                        CampaignCategory.MEDICAL,
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
                title, null, null, null, null, null, goalAmount, currency, null, null, null);
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
    void anotherUsersCampaignReadsAsNotFound() {
        givenCampaign(campaign(creator(true), CampaignLifecycleState.ACTIVE));

        assertThatThrownBy(() -> campaignService.getCampaignById(OTHER_USER_ID, CAMPAIGN_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.NOT_FOUND);
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
}
