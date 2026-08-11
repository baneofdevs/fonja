package com.fonja.verification.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "verification_documents")
public class VerificationDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verification_request_id", nullable = false)
    private VerificationRequest verificationRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocType docType;

    // Local filesystem path today; becomes an object_key on Azure Blob migration.
    // Column name kept generic so the migration doesn't require a rename.
    @Column(name = "file_reference", nullable = false)
    private String fileReference;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected VerificationDocument() {
        // JPA
    }

    public VerificationDocument(VerificationRequest verificationRequest, DocType docType, String fileReference) {
        this.verificationRequest = verificationRequest;
        this.docType = docType;
        this.fileReference = fileReference;
    }

    public Long getId() {
        return id;
    }

    public VerificationRequest getVerificationRequest() {
        return verificationRequest;
    }

    public DocType getDocType() {
        return docType;
    }

    public String getFileReference() {
        return fileReference;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
