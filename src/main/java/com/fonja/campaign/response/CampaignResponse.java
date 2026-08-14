package com.fonja.campaign.response;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.campaign.entity.CampaignLifecycleState;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@AllArgsConstructor
@Getter
@Builder

public class CampaignResponse {

    @NotNull(message = "id is required")
    private Long id;

    @NotNull(message = "creatorId is required")
    private Long creatorId;

    @NotBlank(message = "title is required")
    @Size(max = 150, message = "title must be at most 150 characters")
    private String title;

    @Size(max = 20000, message = "story must be at most 20000 characters")
    private String story;

    @NotNull(message = "category is required")
    private CampaignCategory category;

    private BeneficiaryType beneficiaryType;

    @NotBlank(message = "country is required")
    @Size(max = 100, message = "country must be at most 100 characters")
    private String country;

    @Size(max = 255, message = "location must be at most 255 characters")
    private String location;

    @NotNull(message = "goalAmount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "goalAmount must be greater than 0")
    private BigDecimal goalAmount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code, e.g. NGN")
    private String currency = "NGN";


    @Size(max = 500, message = "coverMediaPath must be at most 500 characters")
    private String coverMediaPath;

    // Optional additional images, in display order.
    private List<String> images;

    @NotNull(message = "lifecycleState is required")
    private CampaignLifecycleState lifecycleState;

    @NotNull(message = "createdAt is required")
    private Instant createdAt;

    @NotNull(message = "updatedAt is required")
    private Instant updatedAt;

    private Instant submittedAt;

    private Instant approvedAt;




}