package com.fonja.donation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fonja.common.exception.ApiException;
import com.fonja.donation.config.CyberPayConfig;
import com.fonja.donation.dto.CyberPayTransaction;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Wraps the CyberPay HTTP API. Currently only the one call the webhook path needs. */
@Service
@EnableConfigurationProperties(CyberPayConfig.class)
public class CyberPayClient {

    private static final Logger log = LoggerFactory.getLogger(CyberPayClient.class);

    /** CyberPay quotes every amount as an integer number of kobo. See {@link #toNaira}. */
    private static final int KOBO_PER_NAIRA_EXPONENT = 2;

    private final RestClient restClient;
    private final CyberPayConfig config;

    public CyberPayClient(CyberPayConfig config) {
        this.config = config;

        // The connect timeout lives on the HttpClient and the read timeout on the factory — they
        // are two different clocks and both have to be set, or one of them is unbounded.
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build());
        requestFactory.setReadTimeout(config.getReadTimeout());
        this.restClient =
                RestClient.builder()
                        .baseUrl(config.getBaseUrl())
                        .requestFactory(requestFactory)
                        .build();
    }

    /**
     * Asks CyberPay what actually happened to a transaction.
     *
     * <p>The verdict is {@code data.status}, not the envelope's {@code succeeded} flag: an
     * abandoned transaction comes back HTTP 200 with {@code succeeded: false} and {@code code:
     * "PY01"}, which is a perfectly successful call reporting an unsuccessful payment. Reading the
     * flag instead of the status would turn every abandonment into a retry loop.
     *
     * @throws ApiException gatewayUnavailable if CyberPay cannot be reached or answers with
     *     something this cannot read — the caller turns that into a 503 and CyberPay redelivers
     */
    public CyberPayTransaction requery(String transactionReference) {
        JsonNode body;
        try {
            body =
                    restClient
                            .get()
                            .uri("/api/v1/payments/{reference}", transactionReference)
                            // Not documented on this endpoint — their Postman example sends no
                            // headers at all — but sending our own key to our own gateway costs
                            // nothing and avoids a 401 if they ever start requiring it.
                            .header("ApiKey", config.getApiKey())
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn(
                    "CyberPay re-query failed for reference={}: {}",
                    transactionReference,
                    e.toString());
            throw ApiException.gatewayUnavailable(
                    "Could not re-query CyberPay for " + transactionReference);
        }

        JsonNode data = body == null ? null : body.get("data");
        if (data == null || data.isNull()) {
            log.warn(
                    "CyberPay re-query for reference={} returned no data node (code={})",
                    transactionReference,
                    body == null ? null : body.path("code").asText(null));
            throw ApiException.gatewayUnavailable(
                    "CyberPay returned no transaction data for " + transactionReference);
        }

        return new CyberPayTransaction(
                data.path("reference").asText(transactionReference),
                data.path("status").asText(null),
                toNaira(data.path("amount")));
    }

    /**
     * Converts CyberPay's integer kobo into naira.
     *
     * <p><strong>Unverified against a live transaction.</strong> Their documentation never states
     * the unit, and their samples are consistent with either reading — a ₦500 payment charged ₦7.50
     * and a ₦50,000 payment charged ₦750 both appear as {@code 50000} / {@code 750}. Kobo is the
     * recorded assumption. If it is wrong, the amount check in {@code DonationService} fails closed
     * on the very first staging donation and logs it, rather than crediting a creator a hundred
     * times what the donor gave. Confirm against a real staging payment before launch.
     */
    private static BigDecimal toNaira(JsonNode amount) {
        if (amount == null || !amount.isNumber()) {
            return null;
        }
        return amount.decimalValue().movePointLeft(KOBO_PER_NAIRA_EXPONENT);
    }
}
