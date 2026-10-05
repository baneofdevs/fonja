package com.fonja.campaign.service;

import com.fonja.campaign.dto.MediaUploadResponse;
import com.fonja.campaign.entity.CampaignMedia;
import com.fonja.campaign.repository.CampaignMediaRepository;
import com.fonja.common.exception.ApiException;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import com.fonja.verification.service.DocumentFormat;
import com.fonja.verification.service.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads and serves campaign images.
 *
 * <p>Shares {@link StorageService} with verification documents but not their visibility: a file is
 * public only if a {@link CampaignMedia} row points at it, and only rows are looked up by the
 * public route. Content is checked by its leading bytes, never by the client's filename or declared
 * type — anything that is not a JPEG, PNG or WebP is refused, PDFs included, because this is served
 * straight into browsers as an image.
 */
@Service
public class CampaignMediaService {

    private static final Logger log = LoggerFactory.getLogger(CampaignMediaService.class);

    /** The public route, and therefore the prefix of every {@code coverMediaPath} written now. */
    public static final String PUBLIC_PATH_PREFIX = "/api/v1/media/";

    private final CampaignMediaRepository mediaRepository;
    private final StorageService storageService;
    private final UserService userService;

    public CampaignMediaService(
            CampaignMediaRepository mediaRepository,
            StorageService storageService,
            UserService userService) {
        this.mediaRepository = mediaRepository;
        this.storageService = storageService;
        this.userService = userService;
    }

    @Transactional
    public MediaUploadResponse upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("An image file is required");
        }
        DocumentFormat format = requireImage(file);
        User owner = userService.requireById(userId);

        String reference;
        try (InputStream content = file.getInputStream()) {
            reference = storageService.store("image." + format.getExtension(), content);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }

        CampaignMedia media =
                mediaRepository.save(new CampaignMedia(owner, reference, format.getContentType()));
        log.info("Media id={} uploaded by user id={} ({})", media.getId(), userId, format);
        return toResponse(media);
    }

    /** Streams a public image. Not owner-scoped: anything with a row is public by definition. */
    @Transactional(readOnly = true)
    public StoredMedia open(Long mediaId) {
        CampaignMedia media =
                mediaRepository
                        .findById(mediaId)
                        .orElseThrow(
                                () -> ApiException.notFound("Media " + mediaId + " not found"));
        return new StoredMedia(
                media.getContentType(), storageService.retrieve(media.getFileReference()));
    }

    public record StoredMedia(String contentType, InputStream content) {}

    private static MediaUploadResponse toResponse(CampaignMedia media) {
        return new MediaUploadResponse(
                media.getId(), PUBLIC_PATH_PREFIX + media.getId(), media.getContentType());
    }

    private static DocumentFormat requireImage(MultipartFile file) {
        byte[] header = new byte[DocumentFormat.HEADER_BYTES];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(header, 0, header.length);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
        DocumentFormat format =
                DocumentFormat.detect(Arrays.copyOf(header, Math.max(read, 0)))
                        .filter(f -> f != DocumentFormat.PDF)
                        .orElse(null);
        if (format == null) {
            throw ApiException.badRequest("Campaign images must be JPEG, PNG or WebP");
        }
        return format;
    }
}
