package com.fonja.donation.dto;

import com.fonja.common.exception.ApiException;
import java.util.Map;
import tools.jackson.databind.JsonNode;

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
 * @param merchantReference the reference we generated and sent as {@code MerchantRef}. The fallback
 *     handle on a donation whose initiate call reached CyberPay but whose response we never managed
 *     to store; may be null on a notification for a payment Fonja did not open
 * @param paymentChannel their channel string, e.g. {@code BankTransfer}; may be null
 */
public record CyberPayWebhookNotification(
        String reference, String merchantReference, String paymentChannel) {

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
        if (reference == null || reference.asString("").isBlank()) {
            throw ApiException.badRequest("Webhook payload has no Data.Reference");
        }

        JsonNode merchantRef = field(data, "merchantref");
        JsonNode channel = field(data, "paymentchannel");
        return new CyberPayWebhookNotification(
                reference.asString().trim(),
                merchantRef == null ? null : merchantRef.asString(null),
                channel == null ? null : channel.asString(null));
    }

    /**
     * Case-insensitive field lookup. CyberPay sends PascalCase on webhooks ({@code Data.Reference})
     * and camelCase on the re-query response ({@code data.reference}) for the same fields, so
     * matching either spelling costs one loop and removes a whole class of silent breakage.
     */
    private static JsonNode field(JsonNode node, String lowercaseName) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (entry.getKey().equalsIgnoreCase(lowercaseName)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
