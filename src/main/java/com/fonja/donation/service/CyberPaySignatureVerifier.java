package com.fonja.donation.service;

import com.fonja.common.exception.ApiException;
import com.fonja.donation.config.CyberPayConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Authenticates a CyberPay webhook.
 *
 * <p>CyberPay signs every notification with the {@code Cyberpay-Signature} header: the hex-encoded
 * HMAC-SHA512 of the <strong>raw request body</strong>, keyed with our integration key. This is the
 * only thing standing between {@code /api/v1/donations/webhook} — which must be {@code permitAll},
 * because CyberPay has no user token to present — and anyone who can POST a JSON body.
 *
 * <p><strong>Raw bytes, not a re-serialised object.</strong> The digest covers the exact bytes on
 * the wire. Binding the body to a DTO and re-encoding it changes key order and whitespace, and the
 * digest will not match. CyberPay's own Node example gets this wrong (it hashes {@code
 * JSON.stringify(payload)}); their PHP example gets it right (it hashes {@code php://input}). The
 * prose is authoritative and says raw body, so the controller takes a {@code byte[]}.
 *
 * <p>Their Java example is also wrong in a second way — it formats each byte with {@code "x"}
 * instead of {@code "%02x"} — which is why this class uses {@link HexFormat} rather than the
 * hand-rolled loop they publish.
 */
@Component
@EnableConfigurationProperties(CyberPayConfig.class)
public class CyberPaySignatureVerifier {

    /**
     * CyberPay's docs spell this both {@code Cyberpay-Signature} and {@code CyberPay-Signature}.
     * HTTP header names are case-insensitive and the servlet container looks them up that way, so
     * either spelling resolves to the same header.
     */
    public static final String SIGNATURE_HEADER = "Cyberpay-Signature";

    private static final String ALGORITHM = "HmacSHA512";
    private static final Logger log = LoggerFactory.getLogger(CyberPaySignatureVerifier.class);

    private final CyberPayConfig config;

    public CyberPaySignatureVerifier(CyberPayConfig config) {
        this.config = config;
    }

    /**
     * Rejects the request unless {@code providedSignature} matches a digest of {@code rawBody}
     * computed with our integration key.
     *
     * @throws ApiException unauthorized on any mismatch, and on a body or header that is missing
     *     entirely
     * @throws ApiException gatewayUnavailable if no integration key is configured — a 503 rather
     *     than a 401 so CyberPay keeps redelivering for the 72 hours it takes us to notice and set
     *     the key, instead of us silently dropping real payments
     */
    public void verify(byte[] rawBody, String providedSignature) {
        String key = config.getIntegrationKey();
        if (key == null || key.isBlank()) {
            log.error(
                    "Rejecting CyberPay webhook: fonja.cyberpay.integration-key is not set, so no "
                            + "signature can be verified. Set CYBERPAY_INTEGRATION_KEY.");
            throw ApiException.gatewayUnavailable("Payment webhook verification is not configured");
        }
        if (rawBody == null || rawBody.length == 0) {
            throw ApiException.unauthorized("Webhook body is empty");
        }
        if (providedSignature == null || providedSignature.isBlank()) {
            throw ApiException.unauthorized("Missing " + SIGNATURE_HEADER + " header");
        }

        byte[] expected = hexDigest(rawBody, key).getBytes(StandardCharsets.US_ASCII);
        byte[] provided =
                providedSignature
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .getBytes(StandardCharsets.US_ASCII);

        // Constant-time. A byte-at-a-time String.equals leaks how much of a forged signature was
        // correct, which is enough to reconstruct the rest one request at a time.
        if (!MessageDigest.isEqual(expected, provided)) {
            // Deliberately does not log either value: the expected digest is derived from the
            // signing key, and an attacker who can read logs should not be handed it.
            throw ApiException.unauthorized("Invalid " + SIGNATURE_HEADER);
        }
    }

    /** Lower-case hex HMAC-SHA512 of {@code payload} under {@code key}. */
    static String hexDigest(byte[] payload, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA512 is required of every JRE, and the key is non-empty by the time we get
            // here, so this is unreachable rather than merely unlikely.
            throw new IllegalStateException("HMAC-SHA512 is unavailable", e);
        }
    }
}
