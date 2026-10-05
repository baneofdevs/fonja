package com.fonja.verification.service.impl;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.verification.service.StorageService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Cloudflare R2 implementation, through R2's S3-compatible API. Active when {@code
 * fonja.storage.type=r2}; the local-disk implementation is the default otherwise.
 *
 * <p>The bucket stays private. Nothing is ever served from R2 directly: campaign images go out
 * through {@code GET /api/v1/media/{id}} and verification documents through the admin review route,
 * both of which read via {@link #retrieve}. That keeps the rule that a file is public only if a
 * {@code campaign_media} row says so, exactly as it was on local disk.
 *
 * <p>References keep the local layout — {@code yyyy/MM/dd/uuid.ext} — so they read the same in the
 * database whichever backend wrote them.
 */
@Service
@ConditionalOnProperty(name = "fonja.storage.type", havingValue = "r2")
public class R2StorageServiceImpl implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(R2StorageServiceImpl.class);
    private static final DateTimeFormatter DATE_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final S3Client s3;
    private final String bucket;

    public R2StorageServiceImpl(
            @Value("${fonja.storage.r2.account-id}") String accountId,
            @Value("${fonja.storage.r2.access-key-id}") String accessKeyId,
            @Value("${fonja.storage.r2.secret-access-key}") String secretAccessKey,
            @Value("${fonja.storage.r2.bucket}") String bucket) {
        this(
                S3Client.builder()
                        .endpointOverride(
                                URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                        // R2 has one region, "auto"; the SDK insists on being given one.
                        .region(Region.of("auto"))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                        .serviceConfiguration(
                                S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        // SDK 2.30+ adds CRC checksums to every request by default, which R2 does
                        // not fully support; Cloudflare's guidance is to send them only when an
                        // operation requires one.
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .build(),
                bucket);
        log.info("Storing documents in R2 bucket {}", bucket);
    }

    R2StorageServiceImpl(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String store(String originalFilename, InputStream content) {
        String reference =
                LocalDate.now().format(DATE_PREFIX)
                        + "/"
                        + UUID.randomUUID()
                        + extensionOf(originalFilename);
        try {
            // Buffered rather than streamed: a put needs its length up front, and uploads are
            // capped at 10 MB by spring.servlet.multipart.
            byte[] bytes = content.readAllBytes();
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(reference).build(),
                    RequestBody.fromBytes(bytes));
        } catch (IOException | SdkException e) {
            throw new ApiException(
                    ResponseCode.STORAGE_ERROR, "Could not store uploaded document", e);
        }
        log.debug("Stored document at reference={}", reference);
        return reference;
    }

    @Override
    public InputStream retrieve(String fileReference) {
        if (!StringUtils.hasText(fileReference)) {
            throw ApiException.badRequest("File reference is required");
        }
        try {
            return s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(fileReference).build());
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("Stored document not found: " + fileReference);
        } catch (SdkException e) {
            throw new ApiException(ResponseCode.STORAGE_ERROR, "Could not read stored document", e);
        }
    }

    @PreDestroy
    void close() {
        s3.close();
    }

    /** Same rule as the local implementation: keep a plain extension, drop anything odd. */
    private static String extensionOf(String originalFilename) {
        String extension =
                StringUtils.getFilenameExtension(
                        StringUtils.cleanPath(originalFilename == null ? "" : originalFilename));
        if (extension == null || !extension.matches("[A-Za-z0-9]{1,10}")) {
            return "";
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }
}
