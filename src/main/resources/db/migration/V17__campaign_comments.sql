-- campaign_comments
-- Matches com.fonja.comment.entity.CampaignComment
--
-- One level of replies: a row with parent_id set answers a top-level comment and is never itself
-- answered. Deletion is soft — the row stays so a thread of replies keeps its context, and the
-- body is withheld from every response.

CREATE TABLE campaign_comments (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT        NOT NULL,
    author_id   BIGINT        NOT NULL,
    parent_id   BIGINT        NULL,
    body        VARCHAR(1000) NOT NULL,
    deleted_at  DATETIME(6)   NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT pk_campaign_comments PRIMARY KEY (id),
    CONSTRAINT fk_campaign_comments_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id),
    CONSTRAINT fk_campaign_comments_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_campaign_comments_parent FOREIGN KEY (parent_id) REFERENCES campaign_comments (id)
) ENGINE = InnoDB;

-- Top-level page for a campaign (parent_id IS NULL), newest first; and replies by parent.
CREATE INDEX idx_campaign_comments_thread ON campaign_comments (campaign_id, parent_id, created_at);
CREATE INDEX idx_campaign_comments_parent ON campaign_comments (parent_id, created_at);
