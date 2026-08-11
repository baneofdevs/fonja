package com.fonja.verification.service.impl;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.verification.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Local filesystem implementation — foundation-stage only.
 * NOTE: local disk is not durable across container restarts or multiple
 * app instances. Must migrate to Azure Blob before any multi-instance
 * or production deployment (see technical spec, Open Decisions).
 */
@Service
public class LocalStorageServiceImpl implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageServiceImpl.class);
    private static final DateTimeFormatter DATE_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path basePath;

    public LocalStorageServiceImpl(@Value("${fonja.storage.local.base-path}") String basePath) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
    }

    @Override
    public String store(String originalFilename, InputStream content) {
        // Date-partitioned so a single directory never accumulates every
        // document ever uploaded.
        String relativeDir = LocalDate.now().format(DATE_PREFIX);
        String storedName = UUID.randomUUID() + extensionOf(originalFilename);
        Path target = basePath.resolve(relativeDir).resolve(storedName);

        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(ResponseCode.STORAGE_ERROR, "Could not store uploaded document", e);
        }

        String reference = relativeDir + "/" + storedName;
        log.debug("Stored document at reference={}", reference);
        return reference;
    }

    @Override
    public InputStream retrieve(String fileReference) {
        Path target = resolveWithinBase(fileReference);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("Stored document not found: " + fileReference);
        }
        try {
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new ApiException(ResponseCode.STORAGE_ERROR, "Could not read stored document", e);
        }
    }

    /**
     * Resolves a reference and refuses anything that escapes the base directory.
     * References come from our own database today, but this is the boundary
     * where a traversal string would do damage, so it is checked here rather
     * than trusted upstream.
     */
    private Path resolveWithinBase(String fileReference) {
        if (!StringUtils.hasText(fileReference)) {
            throw ApiException.badRequest("File reference is required");
        }
        Path resolved = basePath.resolve(fileReference).normalize();
        if (!resolved.startsWith(basePath)) {
            throw ApiException.badRequest("Invalid file reference");
        }
        return resolved;
    }

    /** Preserves the extension for content sniffing on admin download; drops anything suspicious. */
    private String extensionOf(String originalFilename) {
        String extension = StringUtils.getFilenameExtension(
                StringUtils.cleanPath(originalFilename == null ? "" : originalFilename));
        if (extension == null || !extension.matches("[A-Za-z0-9]{1,10}")) {
            return "";
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }
}
