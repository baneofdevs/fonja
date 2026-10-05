-- categories; campaigns.category -> campaigns.category_id
-- Matches com.fonja.campaign.entity.Category
--
-- Categories used to be a Java enum, so adding one meant a deploy and creators whose cause fit
-- none of them had nowhere to go. They are rows now: admins add them at runtime, "others" plus a
-- short free-text note covers the long tail, and the same table feeds onboarding interests.

CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    slug        VARCHAR(60)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    emoji       VARCHAR(16)  NULL,
    sort_order  INT          NOT NULL,
    active      BIT(1)       NOT NULL DEFAULT b'1',
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_slug UNIQUE (slug)
) ENGINE = InnoDB;

-- The Fonja Design System's list, plus faith-religion so RELIGIOUS_CAUSES campaigns keep a home.
-- "others" sorts last whatever gets added later.
INSERT INTO categories (slug, name, emoji, sort_order, active, created_at) VALUES
    ('education',        'Education',                 NULL, 10,   b'1', NOW(6)),
    ('technology',       'Technology',                NULL, 20,   b'1', NOW(6)),
    ('community',        'Community',                 NULL, 30,   b'1', NOW(6)),
    ('business',         'Business',                  NULL, 40,   b'1', NOW(6)),
    ('sports',           'Sports',                    NULL, 50,   b'1', NOW(6)),
    ('emergency',        'Emergency',                 NULL, 60,   b'1', NOW(6)),
    ('family',           'Family',                    NULL, 70,   b'1', NOW(6)),
    ('environment',      'Environment',               NULL, 80,   b'1', NOW(6)),
    ('arts-creativity',  'Arts & Creativity',         NULL, 90,   b'1', NOW(6)),
    ('legal',            'Legal',                     NULL, 100,  b'1', NOW(6)),
    ('nonprofit-social', 'Nonprofit & Social Causes', NULL, 110,  b'1', NOW(6)),
    ('medical-health',   'Medical & Health',          NULL, 120,  b'1', NOW(6)),
    ('events',           'Events',                    NULL, 130,  b'1', NOW(6)),
    ('faith-religion',   'Faith & Religion',          NULL, 140,  b'1', NOW(6)),
    ('others',           'Others',                    NULL, 9999, b'1', NOW(6));

ALTER TABLE campaigns
    ADD COLUMN category_id   BIGINT      NULL AFTER story,
    ADD COLUMN category_note VARCHAR(60) NULL AFTER category_id;

UPDATE campaigns c
    JOIN categories k ON k.slug = CASE c.category
        WHEN 'MEDICAL'               THEN 'medical-health'
        WHEN 'REHABILITATION'        THEN 'medical-health'
        WHEN 'EDUCATION'             THEN 'education'
        WHEN 'EMERGENCY'             THEN 'emergency'
        WHEN 'NGO'                   THEN 'nonprofit-social'
        WHEN 'CSR'                   THEN 'nonprofit-social'
        WHEN 'COMMUNITY_DEVELOPMENT' THEN 'community'
        WHEN 'BUSINESS_SUPPORT'      THEN 'business'
        WHEN 'RELIGIOUS_CAUSES'      THEN 'faith-religion'
        ELSE 'others'
    END
SET c.category_id = k.id;

ALTER TABLE campaigns
    MODIFY COLUMN category_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_campaigns_category FOREIGN KEY (category_id) REFERENCES categories (id),
    DROP COLUMN category;

CREATE INDEX idx_campaigns_category ON campaigns (category_id);
