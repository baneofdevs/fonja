package com.fonja.donation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonja.common.exception.ApiException;
import com.fonja.common.response.CustomResponse;
import com.fonja.donation.dto.CyberPayWebhookNotification;
import com.fonja.donation.service.CyberPaySignatureVerifier;
import com.fonja.donation.service.DonationService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives CyberPay payment notifications.
 *
 * <p>The one endpoint in the service that Spring Security lets through unauthenticated, because
 * CyberPay has no user token to present. It authenticates the caller itself, from the {@code
 * Cyberpay-Signature} header, before anything else happens.
 *
 * <h2>What the status code means to CyberPay</h2>
 *
 * <p>Any non-2xx makes them redeliver, every hour, for 72 hours. So the codes here are a protocol,
 * not decoration:
 *
 * <ul>
 *   <li><b>200</b> — dealt with. Includes outcomes that are not happy: a failed payment, an unknown
 *       reference, an amount that did not match. Retrying none of those would help.
 *   <li><b>401</b> — bad or missing signature. Not CyberPay, or our key is wrong; if it is the
 *       latter, their retries give us 72 hours to fix it and still receive the payment.
 *   <li><b>503</b> — ask again later. CyberPay was unreachable, or has not reached a verdict yet.
 * </ul>
 *
 * <p>CyberPay's docs advise returning 2xx before doing slow work. This handler does the work first,
 * which is the safer trade at Fonja's volume: one bounded re-query plus two inserts, against the
 * alternative of acknowledging a payment we then fail to record. If we do fail, the retry window is
 * the recovery mechanism.
 */
@RestController
@RequestMapping("/api/v1/donations/webhook")
public class DonationWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DonationWebhookController.class);

    /**
     * Its own mapper, not the application's. This one only ever calls {@code readTree}, and keeping
     * it local means a future global Jackson setting — a naming strategy, a failure mode — cannot
     * change how a signed payload is read.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CyberPaySignatureVerifier signatureVerifier;
    private final DonationService donationService;

    public DonationWebhookController(
            CyberPaySignatureVerifier signatureVerifier, DonationService donationService) {
        this.signatureVerifier = signatureVerifier;
        this.donationService = donationService;
    }

    /**
     * The body is bound as {@code byte[]}, never as a DTO. The signature covers the exact bytes
     * CyberPay sent, and Jackson round-tripping through an object would change key order and
     * whitespace — the digest would then never match. Parsing happens only after the bytes have
     * been proven authentic.
     */
    @PostMapping
    public CustomResponse<Void> receive(
            @RequestHeader(name = CyberPaySignatureVerifier.SIGNATURE_HEADER, required = false)
                    String signature,
            @RequestBody(required = false) byte[] rawBody) {

        signatureVerifier.verify(rawBody, signature);

        CyberPayWebhookNotification notification = CyberPayWebhookNotification.from(parse(rawBody));
        log.info("CyberPay notification accepted for reference={}", notification.reference());

        donationService.confirmFromWebhook(notification.reference(), notification.paymentChannel());

        return CustomResponse.success("Notification accepted", null);
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            return JSON.readTree(rawBody);
        } catch (IOException e) {
            // Signed, so it did come from CyberPay — but unparseable, and redelivering the same
            // bytes will not fix that. 400 stops the retries and leaves a log to chase.
            log.error("Signed CyberPay notification was not valid JSON", e);
            throw ApiException.badRequest("Webhook payload is not valid JSON");
        }
    }
}
