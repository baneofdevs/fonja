package com.fonja.notification.service;

/**
 * What the notification module needs from an email provider, and nothing more. An interface for the
 * same reason {@code PayoutGateway} and {@code StorageService} are: the provider is a decision that
 * can change, and the messages should not have to.
 */
public interface EmailSender {

    /** Whether sending is possible at all. False means every notification is logged as SKIPPED. */
    boolean isAvailable();

    /**
     * Sends one email as multipart/alternative: the branded HTML, with the plain text as the
     * fallback for clients that do not render HTML (and for spam filters, which trust a message
     * more when both parts say the same thing).
     *
     * @throws EmailDeliveryException if the provider refused or could not be reached; the message
     *     is what gets written to the notification log
     */
    void send(String toAddress, String toName, String subject, String textBody, String htmlBody);

    class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
