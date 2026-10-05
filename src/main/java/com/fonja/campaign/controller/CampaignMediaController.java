package com.fonja.campaign.controller;

import com.fonja.campaign.dto.MediaUploadResponse;
import com.fonja.campaign.service.CampaignMediaService;
import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Two routes on purpose. Upload sits under {@code /campaigns} with every other creator action; the
 * public read lives at {@code /media} so {@code SecurityConfig} can open exactly that prefix and
 * nothing else.
 */
@RestController
@RequestMapping("/api/v1")
public class CampaignMediaController {

    private final CampaignMediaService mediaService;

    public CampaignMediaController(CampaignMediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping(value = "/campaigns/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CustomResponse<MediaUploadResponse> upload(
            @AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        return CustomResponse.success(
                "Image uploaded", mediaService.upload(JwtService.userIdFrom(jwt), file));
    }

    /** Public. Ids are never reused, so the response can be cached for as long as anyone likes. */
    @GetMapping("/media/{mediaId}")
    public ResponseEntity<InputStreamResource> serve(@PathVariable Long mediaId) {
        CampaignMediaService.StoredMedia media = mediaService.open(mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(new InputStreamResource(media.content()));
    }
}
