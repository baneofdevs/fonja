package com.fonja.campaign.entity;

import jakarta.persistence.*;
import lombok.Getter;

/** An additional campaign image, beyond the single cover on {@link Campaign}. */
@Entity
@Table(name = "campaign_images")
@Getter
public class CampaignImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    /** Display position, ascending. Assigned by the service as current max + 1. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CampaignImage() {
        // JPA
    }

    public CampaignImage(Campaign campaign, String filePath, int sortOrder) {
        this.campaign = campaign;
        this.filePath = filePath;
        this.sortOrder = sortOrder;
    }
}
