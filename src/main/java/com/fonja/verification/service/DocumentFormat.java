package com.fonja.verification.service;

import java.util.Optional;

/**
 * The formats a verification document is allowed to be: photographs of an ID, or a PDF certificate.
 *
 * <p>Detection is by magic bytes only. The client-declared {@code Content-Type} and the filename
 * extension are both supplied by whoever is uploading and neither says anything about what the
 * bytes actually are — a text file called {@code id.png} sailed through before this existed.
 *
 * <p>Known gap: HEIC, which is what an iPhone camera produces natively. Most upload paths transcode
 * to JPEG first, so this is deliberate for now rather than overlooked; add it here when a real
 * device test says otherwise.
 */
public enum DocumentFormat {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    PDF("application/pdf", "pdf");

    /** Bytes needed to identify the longest signature below — WebP's marker sits at offset 8. */
    public static final int HEADER_BYTES = 12;

    private final String contentType;
    private final String extension;

    DocumentFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    /** Canonical extension, used for the stored filename so it always matches the real content. */
    public String getExtension() {
        return extension;
    }

    /**
     * Identifies the format from the leading bytes of a file, or empty if it is not one this
     * service accepts.
     */
    public static Optional<DocumentFormat> detect(byte[] header) {
        if (header == null) {
            return Optional.empty();
        }
        if (matchesAt(header, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (matchesAt(header, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        // RIFF container; the WEBP marker follows a 4-byte length field.
        if (matchesAt(header, 0, 'R', 'I', 'F', 'F') && matchesAt(header, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        if (matchesAt(header, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    private static boolean matchesAt(byte[] header, int offset, int... signature) {
        if (header.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((header[offset + i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** Human-readable list for error messages, so a rejection says what would have worked. */
    public static String supportedList() {
        return "JPEG, PNG, WebP or PDF";
    }
}
