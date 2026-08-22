package com.fonja.verification.service;

import java.io.InputStream;

/**
 * Abstraction over document storage so the verification module never depends on where files
 * physically live. Implemented today as local filesystem writes (see impl/LocalStorageServiceImpl);
 * swap in an object-storage implementation later without touching any calling code.
 */
public interface StorageService {

    /**
     * Stores the file and returns a reference (local path today, object key after the Blob
     * migration) to persist on the entity.
     */
    String store(String originalFilename, InputStream content);

    /** Resolves a stored reference back to a readable stream for admin review. */
    InputStream retrieve(String fileReference);
}
