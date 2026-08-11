-- verification_requests, verification_documents
-- Matches com.fonja.verification.entity.*

CREATE TABLE verification_requests (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    type                VARCHAR(255) NOT NULL,
    registration_number VARCHAR(255) NULL,
    status              VARCHAR(255) NOT NULL,
    rejection_reason    VARCHAR(255) NULL,
    reviewed_by         BIGINT       NULL,
    submitted_at        DATETIME(6)  NOT NULL,
    reviewed_at         DATETIME(6)  NULL,
    CONSTRAINT pk_verification_requests PRIMARY KEY (id),
    CONSTRAINT fk_verification_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_verification_requests_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id)
) ENGINE = InnoDB;

-- Admin review queue reads by status; the creator's own gate check reads by user.
CREATE INDEX idx_verification_requests_status ON verification_requests (status);
CREATE INDEX idx_verification_requests_user_status ON verification_requests (user_id, status);

CREATE TABLE verification_documents (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    verification_request_id BIGINT       NOT NULL,
    doc_type                VARCHAR(255) NOT NULL,
    -- Local filesystem reference today; becomes an Azure Blob object key later.
    -- Never the file bytes themselves. See technical spec §6.
    file_reference          VARCHAR(512) NOT NULL,
    uploaded_at             DATETIME(6)  NOT NULL,
    CONSTRAINT pk_verification_documents PRIMARY KEY (id),
    CONSTRAINT fk_verification_documents_request FOREIGN KEY (verification_request_id)
        REFERENCES verification_requests (id)
) ENGINE = InnoDB;

CREATE INDEX idx_verification_documents_request ON verification_documents (verification_request_id);
