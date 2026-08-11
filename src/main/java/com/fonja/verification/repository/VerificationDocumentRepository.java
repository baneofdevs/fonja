package com.fonja.verification.repository;

import com.fonja.verification.entity.VerificationDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VerificationDocumentRepository extends JpaRepository<VerificationDocument, Long> {

    List<VerificationDocument> findByVerificationRequestIdOrderByUploadedAtAsc(Long verificationRequestId);
}
