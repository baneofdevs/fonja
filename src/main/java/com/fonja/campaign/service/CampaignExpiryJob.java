package com.fonja.campaign.service;

import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignLifecycleState;
import com.fonja.campaign.repository.CampaignRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves ACTIVE campaigns past their deadline to EXPIRED. Without this the EXPIRED state is
 * unreachable — nothing else in the system watches the clock.
 *
 * <p><b>Caveat:</b> this is an unsynchronised in-process schedule, so on more than one instance
 * every instance runs the sweep. Expiry is idempotent (the transition is guarded on ACTIVE, so a
 * second pass is a no-op) which makes that harmless today, but the same caveat as local file
 * storage applies: before running multiple instances this wants a shared lock or a real scheduler.
 */
@Component
public class CampaignExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignExpiryJob.class);

    private final CampaignRepository campaignRepository;

    public CampaignExpiryJob(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    /** Hourly: a campaign expiring up to an hour late is not worth tighter scheduling. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    public void expirePastDeadlineCampaigns() {
        List<Campaign> due =
                campaignRepository.findTop200ByLifecycleStateAndDeadlineLessThanEqual(
                        CampaignLifecycleState.ACTIVE, Instant.now());
        if (due.isEmpty()) {
            return;
        }

        for (Campaign campaign : due) {
            campaign.expire();
        }
        log.info("Expired {} campaign(s) past their deadline", due.size());
    }
}
