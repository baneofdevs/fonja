package com.fonja.verification.repository;

import com.fonja.verification.entity.VerificationDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationDocumentRepository extends JpaRepository<VerificationDocument, Long> {

    List<VerificationDocument> findByVerificationRequestIdOrderByUploadedAtAsc(
            Long verificationRequestId);
}
