package com.fonja.campaign.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "campaign_images")
public class CampaignImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "file_path", nullable = false)
    private String filePath;

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

    public Long getId() {
        return id;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
