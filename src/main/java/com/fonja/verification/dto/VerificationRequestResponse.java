package com.fonja.verification.dto;

import com.fonja.verification.entity.VerificationDocument;
import com.fonja.verification.entity.VerificationRequest;
import com.fonja.verification.entity.VerificationStatus;
import com.fonja.verification.entity.VerificationType;
import java.time.Instant;
import java.util.List;

public record VerificationRequestResponse(
        Long id,
        Long userId,
        VerificationType type,
        String registrationNumber,
        VerificationStatus status,
        String rejectionReason,
        Instant submittedAt,
        Instant reviewedAt,
        List<DocumentSummary> documents) {

    /**
     * Document metadata only. The storage reference is intentionally absent — clients fetch bytes
     * through the download endpoint, which authorises the caller; handing out the reference would
     * bypass that.
     */
    public record DocumentSummary(
            Long id, com.fonja.verification.entity.DocType docType, Instant uploadedAt) {

        static DocumentSummary from(VerificationDocument document) {
            return new DocumentSummary(
                    document.getId(), document.getDocType(), document.getUploadedAt());
        }
    }

    public static VerificationRequestResponse from(
            VerificationRequest request, List<VerificationDocument> documents) {
        return new VerificationRequestResponse(
                request.getId(),
                request.getUser().getId(),
                request.getType(),
                request.getRegistrationNumber(),
                request.getStatus(),
                request.getRejectionReason(),
                request.getSubmittedAt(),
                request.getReviewedAt(),
                documents.stream().map(DocumentSummary::from).toList());
    }
}
