-- campaigns, campaign_images
-- Matches com.fonja.campaign.entity.*
--
-- Schema only. The campaign module's behaviour (draft CRUD, feed, Launch gate)
-- lands on Day 2; these tables exist now so Hibernate's `ddl-auto: validate`
-- can validate the whole entity graph at boot.

CREATE TABLE campaigns (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    creator_id        BIGINT         NOT NULL,
    title             VARCHAR(255)   NOT NULL,
    story             TEXT           NULL,
    category          VARCHAR(255)   NOT NULL,
    beneficiary_type  VARCHAR(255)   NULL,
    country           VARCHAR(255)   NOT NULL,
    location          VARCHAR(255)   NULL,
    goal_amount       DECIMAL(14, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'NGN',
    auto_adjust_goal  BIT(1)         NOT NULL DEFAULT b'0',
    cover_media_path  VARCHAR(512)   NULL,
    lifecycle_state   VARCHAR(255)   NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    submitted_at      DATETIME(6)    NULL,
    approved_at       DATETIME(6)    NULL,
    CONSTRAINT pk_campaigns PRIMARY KEY (id),
    CONSTRAINT fk_campaigns_creator FOREIGN KEY (creator_id) REFERENCES users (id)
) ENGINE = InnoDB;

-- The public feed reads ACTIVE only (technical spec §4.3).
CREATE INDEX idx_campaigns_lifecycle_state ON campaigns (lifecycle_state);
CREATE INDEX idx_campaigns_creator ON campaigns (creator_id);

CREATE TABLE campaign_images (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT       NOT NULL,
    file_path   VARCHAR(512) NOT NULL,
    sort_order  INT          NOT NULL,
    CONSTRAINT pk_campaign_images PRIMARY KEY (id),
    CONSTRAINT fk_campaign_images_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id)
) ENGINE = InnoDB;

CREATE INDEX idx_campaign_images_campaign ON campaign_images (campaign_id, sort_order);
