package com.fonja.verification.service;

import com.fonja.common.exception.ApiException;
import com.fonja.user.entity.User;
import com.fonja.user.service.UserService;
import com.fonja.verification.dto.SubmitVerificationRequest;
import com.fonja.verification.dto.VerificationRequestResponse;
import com.fonja.verification.entity.*;
import com.fonja.verification.repository.VerificationDocumentRepository;
import com.fonja.verification.repository.VerificationRequestRepository;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The single creator-verification gate. Approval is what turns a donor into a creator: it sets
 * {@code verified} and grants {@code CREATOR} in one step, and it happens once — future campaign
 * Launches skip this entirely (technical spec §4.2).
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final VerificationRequestRepository requestRepository;
    private final VerificationDocumentRepository documentRepository;
    private final StorageService storageService;
    private final UserService userService;

    public VerificationService(
            VerificationRequestRepository requestRepository,
            VerificationDocumentRepository documentRepository,
            StorageService storageService,
            UserService userService) {
        this.requestRepository = requestRepository;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.userService = userService;
    }

    @Transactional
    public VerificationRequestResponse submit(Long userId, SubmitVerificationRequest command) {
        User user = userService.requireById(userId);

        if (user.isVerified()) {
            throw ApiException.conflict("You are already verified");
        }
        requestRepository
                .findByUserIdAndStatus(userId, VerificationStatus.PENDING)
                .ifPresent(
                        existing -> {
                            throw ApiException.conflict(
                                    "Verification request "
                                            + existing.getId()
                                            + " is already awaiting review");
                        });

        String registrationNumber = normaliseRegistrationNumber(command);
        VerificationRequest request =
                requestRepository.save(
                        new VerificationRequest(user, command.type(), registrationNumber));

        log.info("Verification request id={} submitted by user id={}", request.getId(), userId);
        return VerificationRequestResponse.from(request, List.of());
    }

    /**
     * Business and NGO verification is against a registration number; individual verification is
     * against a government ID document, so a registration number on an INDIVIDUAL request is
     * meaningless and is dropped rather than stored.
     */
    private String normaliseRegistrationNumber(SubmitVerificationRequest command) {
        boolean requiresRegistration =
                command.type() == VerificationType.BUSINESS
                        || command.type() == VerificationType.NGO;
        String value = command.registrationNumber();

        if (requiresRegistration) {
            if (value == null || value.isBlank()) {
                throw ApiException.badRequest(
                        "registrationNumber is required for " + command.type() + " verification");
            }
            return value.trim();
        }
        return null;
    }

    @Transactional
    public VerificationRequestResponse uploadDocument(
            Long userId, Long requestId, DocType docType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("A document file is required");
        }

        VerificationRequest request = requireOwnedRequest(userId, requestId);
        if (!request.isPending()) {
            throw ApiException.conflict(
                    "Verification request " + requestId + " has already been reviewed");
        }

        String fileReference;
        try (var content = file.getInputStream()) {
            fileReference = storageService.store(file.getOriginalFilename(), content);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }

        documentRepository.save(new VerificationDocument(request, docType, fileReference));

        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public VerificationRequestResponse getLatestForUser(Long userId) {
        VerificationRequest request =
                requestRepository
                        .findFirstByUserIdOrderBySubmittedAtDesc(userId)
                        .orElseThrow(() -> ApiException.notFound("No verification request found"));
        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public Page<VerificationRequestResponse> reviewQueue(
            VerificationStatus status, Pageable pageable) {
        return requestRepository
                .findByStatusOrderBySubmittedAtAsc(status, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public VerificationRequestResponse getById(Long requestId) {
        return toResponse(requireRequest(requestId));
    }

    /**
     * Admin approval. Sets the request APPROVED and promotes the user in the same transaction — a
     * half-applied approval would leave a user who has been told they are verified but cannot
     * launch a campaign.
     */
    @Transactional
    public VerificationRequestResponse approve(Long adminUserId, Long requestId) {
        VerificationRequest request = requireRequest(requestId);
        User admin = userService.requireById(adminUserId);

        transition(() -> request.approve(admin));
        userService.markVerified(request.getUser().getId());

        log.info("Verification request id={} approved by admin id={}", requestId, adminUserId);
        return toResponse(request);
    }

    @Transactional
    public VerificationRequestResponse reject(Long adminUserId, Long requestId, String reason) {
        VerificationRequest request = requireRequest(requestId);
        User admin = userService.requireById(adminUserId);

        transition(() -> request.reject(admin, reason));

        log.info("Verification request id={} rejected by admin id={}", requestId, adminUserId);
        return toResponse(request);
    }

    /** Streams a stored document to an admin reviewer. */
    @Transactional(readOnly = true)
    public StoredDocument download(Long documentId) {
        VerificationDocument document =
                documentRepository
                        .findById(documentId)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "Document " + documentId + " not found"));
        return new StoredDocument(
                document.getDocType(), storageService.retrieve(document.getFileReference()));
    }

    /**
     * Turns the entity's own "already decided" guard into an API-level conflict. Two admins opening
     * the same queue item is ordinary, not exceptional.
     */
    private void transition(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
    }

    private VerificationRequest requireRequest(Long requestId) {
        return requestRepository
                .findById(requestId)
                .orElseThrow(
                        () ->
                                ApiException.notFound(
                                        "Verification request " + requestId + " not found"));
    }

    private VerificationRequest requireOwnedRequest(Long userId, Long requestId) {
        VerificationRequest request = requireRequest(requestId);
        if (!request.getUser().getId().equals(userId)) {
            // Same response as a missing request: telling a caller that someone
            // else's request exists is itself a leak.
            throw ApiException.notFound("Verification request " + requestId + " not found");
        }
        return request;
    }

    private VerificationRequestResponse toResponse(VerificationRequest request) {
        return VerificationRequestResponse.from(
                request,
                documentRepository.findByVerificationRequestIdOrderByUploadedAtAsc(
                        request.getId()));
    }

    /** A document's bytes plus the type an admin needs to interpret them. */
    public record StoredDocument(DocType docType, java.io.InputStream content) {}
}
