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

    // TODO: constructor, getters/setters per team convention
}
