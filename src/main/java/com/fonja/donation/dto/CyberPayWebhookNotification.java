package com.fonja.donation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fonja.common.exception.ApiException;
import java.util.Iterator;
import java.util.Map;

/**
 * The two fields the webhook handler takes from a notification body.
 *
 * <p>Deliberately not a full mapping of CyberPay's payload. The body carries a status, an amount
 * and a charge, and none of them are used: the handler re-queries CyberPay for those. Narrowing the
 * type to what we actually consume makes it impossible for a later edit to quietly start trusting
 * the amount in the message.
 *
 * <p>Parsed from a {@link JsonNode} rather than bound by Jackson because the body arrives as a
 * {@code byte[]} — the signature covers the raw bytes, so nothing may re-serialise it.
 *
 * @param reference CyberPay's transaction reference, which is what {@code GET
 *     /api/v1/payments/{reference}} takes
 * @param paymentChannel their channel string, e.g. {@code BankTransfer}; may be null
 */
public record CyberPayWebhookNotification(String reference, String paymentChannel) {

    /**
     * @throws ApiException badRequest if there is no reference to re-query, which makes the message
     *     unusable no matter how well it is signed
     */
    public static CyberPayWebhookNotification from(JsonNode root) {
        JsonNode data = root == null ? null : field(root, "data");
        if (data == null || data.isNull()) {
            throw ApiException.badRequest("Webhook payload has no Data object");
        }

        JsonNode reference = field(data, "reference");
        if (reference == null || reference.asText("").isBlank()) {
            throw ApiException.badRequest("Webhook payload has no Data.Reference");
        }

        JsonNode channel = field(data, "paymentchannel");
        return new CyberPayWebhookNotification(
                reference.asText().trim(), channel == null ? null : channel.asText(null));
    }

    /**
     * Case-insensitive field lookup. CyberPay sends PascalCase on webhooks ({@code Data.Reference})
     * and camelCase on the re-query response ({@code data.reference}) for the same fields, so
     * matching either spelling costs one loop and removes a whole class of silent breakage.
     */
    private static JsonNode field(JsonNode node, String lowercaseName) {
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().equalsIgnoreCase(lowercaseName)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
