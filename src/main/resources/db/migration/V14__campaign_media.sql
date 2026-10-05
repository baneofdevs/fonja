-- campaign_media
-- Matches com.fonja.campaign.entity.CampaignMedia
--
-- Every image a creator uploads for a campaign gets a row here, and the public
-- GET /api/v1/media/{id} serves only what is in this table. Verification documents share
-- the same storage directory but never appear here, which is what keeps an ID scan from
-- being one guessable URL away from public.

CREATE TABLE campaign_media (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id       BIGINT       NOT NULL,
    file_reference VARCHAR(512) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_campaign_media PRIMARY KEY (id),
    CONSTRAINT fk_campaign_media_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_campaign_media_owner ON campaign_media (owner_id);
