package com.fonja.verification.controller;

import com.fonja.common.response.CustomResponse;
import com.fonja.common.security.JwtService;
import com.fonja.verification.dto.RejectVerificationRequest;
import com.fonja.verification.dto.SubmitVerificationRequest;
import com.fonja.verification.dto.VerificationRequestResponse;
import com.fonja.verification.entity.DocType;
import com.fonja.verification.entity.VerificationStatus;
import com.fonja.verification.service.VerificationService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Creator-facing endpoints live at the root; admin review lives under
 * {@code /admin}, which {@code SecurityConfig} restricts to {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/verification")
public class VerificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping
    public CustomResponse<VerificationRequestResponse> submit(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody SubmitVerificationRequest request) {
        return CustomResponse.success("Verification request submitted",
                verificationService.submit(JwtService.userIdFrom(jwt), request));
    }

    @GetMapping("/me")
    public CustomResponse<VerificationRequestResponse> myLatest(@AuthenticationPrincipal Jwt jwt) {
        return CustomResponse.success(verificationService.getLatestForUser(JwtService.userIdFrom(jwt)));
    }

    @PostMapping(value = "/{requestId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CustomResponse<VerificationRequestResponse> uploadDocument(@AuthenticationPrincipal Jwt jwt,
                                                                      @PathVariable Long requestId,
                                                                      @RequestParam DocType docType,
                                                                      @RequestPart("file") MultipartFile file) {
        return CustomResponse.success("Document uploaded",
                verificationService.uploadDocument(JwtService.userIdFrom(jwt), requestId, docType, file));
    }

    @GetMapping("/admin/requests")
    public CustomResponse<Page<VerificationRequestResponse>> reviewQueue(
            @RequestParam(defaultValue = "PENDING") VerificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return CustomResponse.success(verificationService.reviewQueue(status, pageable));
    }

    @GetMapping("/admin/requests/{requestId}")
    public CustomResponse<VerificationRequestResponse> getRequest(@PathVariable Long requestId) {
        return CustomResponse.success(verificationService.getById(requestId));
    }

    @PostMapping("/admin/requests/{requestId}/approve")
    public CustomResponse<VerificationRequestResponse> approve(@AuthenticationPrincipal Jwt jwt,
                                                               @PathVariable Long requestId) {
        return CustomResponse.success("Verification approved",
                verificationService.approve(JwtService.userIdFrom(jwt), requestId));
    }

    @PostMapping("/admin/requests/{requestId}/reject")
    public CustomResponse<VerificationRequestResponse> reject(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable Long requestId,
                                                              @Valid @RequestBody RejectVerificationRequest request) {
        return CustomResponse.success("Verification rejected",
                verificationService.reject(JwtService.userIdFrom(jwt), requestId, request.reason()));
    }

    /**
     * Streams the raw document for review. Served as an attachment with an
     * opaque filename so a KYC document is never rendered inline in the
     * reviewer's browser.
     */
    @GetMapping("/admin/documents/{documentId}")
    public ResponseEntity<InputStreamResource> downloadDocument(@PathVariable Long documentId) {
        VerificationService.StoredDocument document = verificationService.download(documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.docType().name().toLowerCase() + "-" + documentId + "\"")
                .body(new InputStreamResource(document.content()));
    }
}
