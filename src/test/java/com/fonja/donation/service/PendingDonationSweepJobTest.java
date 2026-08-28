package com.fonja.donation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.repository.DonationRepository;
import com.fonja.donation.service.DonationService.SettlementOutcome;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The job's own job is batching and isolation — deciding what happens to a donation belongs to
 * {@link DonationService} and is tested there. What matters here is that the window it asks for is
 * the right one, and that one broken donation cannot take the batch down with it.
 */
@ExtendWith(MockitoExtension.class)
class PendingDonationSweepJobTest {

    @Mock private DonationRepository donationRepository;
    @Mock private DonationService donationService;

    @org.mockito.InjectMocks private PendingDonationSweepJob job;

    @Test
    void asksForPendingDonationsOldEnoughToBeOffTheHappyPathButNotYetAbandoned() {
        givenPending();

        job.sweepPendingDonations();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(donationRepository)
                .findTop100ByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                        eq(DonationStatus.PENDING), from.capture(), to.capture());

        Instant now = Instant.now();
        // Recent end: donations younger than the grace period are excluded, because a donor may
        // still be on the checkout page and re-querying them is pure waste.
        assertThat(Duration.between(to.getValue(), now)).isCloseTo(Duration.ofMinutes(30), Duration.ofMinutes(1));
        // Old end: past this, CyberPay's 72 hours of retries are long over and nothing the sweep
        // does will change the answer.
        assertThat(Duration.between(from.getValue(), now)).isCloseTo(Duration.ofDays(7), Duration.ofMinutes(1));
    }

    @Test
    void reconcilesEveryDonationInTheBatch() {
        givenPending(1L, 2L, 3L);
        when(donationService.reconcilePending(anyLong())).thenReturn(SettlementOutcome.SETTLED);

        job.sweepPendingDonations();

        verify(donationService).reconcilePending(1L);
        verify(donationService).reconcilePending(2L);
        verify(donationService).reconcilePending(3L);
    }

    @Test
    void carriesOnAfterOneDonationFails() {
        // An unreachable gateway on one donation must not hide the ninety-nine behind it. This is
        // why the job is not @Transactional and settles each row separately.
        givenPending(1L, 2L, 3L);
        when(donationService.reconcilePending(1L))
                .thenThrow(com.fonja.common.exception.ApiException.gatewayUnavailable("down"));
        when(donationService.reconcilePending(2L)).thenReturn(SettlementOutcome.SETTLED);
        when(donationService.reconcilePending(3L)).thenReturn(SettlementOutcome.FAILED);

        assertThatCode(job::sweepPendingDonations).doesNotThrowAnyException();

        verify(donationService).reconcilePending(2L);
        verify(donationService).reconcilePending(3L);
    }

    @Test
    void doesNothingWhenNothingIsPending() {
        givenPending();

        job.sweepPendingDonations();

        verify(donationService, never()).reconcilePending(anyLong());
    }

    @Test
    void countsButDoesNotSweepDonationsItHasGivenUpOn() {
        givenPending();
        when(donationRepository.countByStatusAndCreatedAtBefore(eq(DonationStatus.PENDING), any()))
                .thenReturn(4L);

        // The count is a log line, not a state change: these rows need a person, and re-querying
        // them hourly forever would cost more every week and resolve nothing.
        assertThatCode(job::sweepPendingDonations).doesNotThrowAnyException();

        verify(donationService, never()).reconcilePending(anyLong());
    }

    // --- helpers ---------------------------------------------------------------------

    private void givenPending(Long... ids) {
        List<Donation> due = new ArrayList<>();
        for (Long id : ids) {
            due.add(donation(id));
        }
        when(donationRepository.findTop100ByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
                        any(), any(), any()))
                .thenReturn(due);
    }

    private Donation donation(Long id) {
        User creator = new User("creator@b.com", "C D", AuthProvider.GOOGLE, "sub-2");
        ReflectionTestUtils.setField(creator, "id", 2L);
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
        User donor = new User("a@b.com", "A B", AuthProvider.GOOGLE, "sub-1");
        Donation donation =
                new Donation(campaign, donor, new BigDecimal("5000.00"), "NGN", "FNJ" + id);
        ReflectionTestUtils.setField(donation, "id", id);
        return donation;
    }
}
