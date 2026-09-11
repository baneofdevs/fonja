package com.fonja.payout.service;

import com.fonja.common.exception.ApiException;
import com.fonja.payout.config.PayoutConfig;
import com.fonja.payout.dto.PayoutDispatch;
import com.fonja.payout.dto.PayoutInstruction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * CyberPay's PayOut API — a different service from the one that takes donations, and not just a
 * different path on it.
 *
 * <p>Three things differ from {@code CyberPayClient} and all three are load-bearing. Auth is OAuth2
 * {@code client_credentials} rather than an {@code ApiKey} header. Responses are PascalCase where
 * collections are camelCase, and their {@code Data} field is a bare string rather than an object.
 * And a successful call means <em>accepted</em>, not <em>paid</em>: {@code Code: "PR"} is "your
 * request is being processed".
 *
 * <p><strong>This has never run against a live endpoint.</strong> It is written to CyberPay's
 * documented request and response shapes for {@code POST /instant}, which is the one payout
 * endpoint they publish both halves of. {@code PAYOUT_BASE_URL}, {@code AUTHORIZATION_URL} and the
 * wallet code are not in their collection at all and have to come from CyberPay directly, so {@link
 * PayoutConfig#isConfigured()} is false out of the box and {@link #isAvailable()} reports it.
 * Parsing is deliberately forgiving about case and field names — their documentation is
 * inconsistent enough that assuming one spelling would be optimistic.
 */
@Service
@EnableConfigurationProperties(PayoutConfig.class)
public class CyberPayPayoutClient implements PayoutGateway {

    private static final Logger log = LoggerFactory.getLogger(CyberPayPayoutClient.class);

    /** Amounts are integer kobo here exactly as they are on the collections API. */
    private static final int KOBO_PER_NAIRA_EXPONENT = 2;

    /** "Processing" — the documented success code for an accepted instruction. */
    private static final String CODE_PROCESSING = "PR";

    /**
     * Renew this far before a token actually expires. A token that dies mid-flight costs a payout
     * instruction whose outcome we then cannot be sure of, which is much more expensive than an
     * extra token call.
     */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(2);

    private final PayoutConfig config;
    private final RestClient payoutClient;
    private final RestClient authClient;

    /**
     * Cached bearer token. Atomic rather than synchronized: two threads racing at expiry both fetch
     * a token and one wins the swap, which wastes a call and breaks nothing. Locking here would put
     * every payout behind a mutex to save that.
     */
    private final AtomicReference<CachedToken> token = new AtomicReference<>();

    public CyberPayPayoutClient(PayoutConfig config) {
        this.config = config;
        this.payoutClient = build(config.getBaseUrl(), config);
        this.authClient = build(config.getAuthorizationUrl(), config);

        if (!config.isConfigured()) {
            // One line at startup, rather than a stack trace per withdrawal. Payouts still work —
            // they wait for an admin instead of going out automatically.
            log.warn(
                    "CyberPay payouts are not configured (fonja.payout.*). Withdrawals will be "
                            + "reserved and left REQUESTED for manual settlement.");
        }
    }

    private static RestClient build(String baseUrl, PayoutConfig config) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build());
        requestFactory.setReadTimeout(config.getReadTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        // An empty base URL is legal to build and never called: isAvailable() gates every use.
        return baseUrl.isBlank() ? builder.build() : builder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean isAvailable() {
        return config.isConfigured();
    }

    /**
     * {@inheritDoc}
     *
     * <p>CyberPay documents this endpoint's request body and not its response, so every plausible
     * spelling of "the account holder's name" is tried before giving up. Failing soft is the whole
     * design: an unreachable name enquiry must not stop a creator linking their account, because
     * the transfer is validated against the name at NIBSS whether we looked it up or not.
     */
    @Override
    public Optional<String> lookupAccountName(String bankCode, String accountNumber) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        JsonNode body;
        try {
            body =
                    payoutClient
                            .post()
                            .uri("/api/v1/account/name-enquiry")
                            .header("Authorization", "Bearer " + bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("bankCode", bankCode, "accountNumber", accountNumber))
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientException | ApiException e) {
            log.warn("Name enquiry failed for bankCode={}: {}", bankCode, e.toString());
            return Optional.empty();
        }

        if (body == null) {
            return Optional.empty();
        }
        JsonNode data = firstPresent(body, "data", "Data");
        JsonNode source = data != null && data.isObject() ? data : body;
        JsonNode name = firstPresent(source, "accountName", "AccountName", "name", "Name");

        return Optional.ofNullable(name == null ? null : name.asString(null))
                .filter(value -> !value.isBlank());
    }

    @Override
    public PayoutDispatch send(PayoutInstruction instruction) {
        if (!isAvailable()) {
            throw ApiException.gatewayUnavailable("Payouts are not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("WalletCode", config.getWalletCode());
        body.put("WalletType", config.getWalletType());
        body.put("BeneficiaryName", instruction.beneficiaryName());
        body.put("SenderName", config.getSenderName());
        body.put("Amount", toKobo(instruction.amount()));
        body.put("AccountNumber", instruction.accountNumber());
        body.put("BankCode", instruction.bankCode());
        body.put("MerchantRef", instruction.merchantReference());
        body.put("Narration", instruction.narration());
        // Their example sends both. UseWalletProvider false is the plain NIP transfer.
        body.put("WebHook", "");
        body.put("UseWalletProvider", false);

        JsonNode response;
        try {
            response =
                    payoutClient
                            .post()
                            .uri("/instant")
                            .header("Authorization", "Bearer " + bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientException e) {
            // Nothing here distinguishes "never arrived" from "arrived and the reply was lost", so
            // the caller must treat this as unknown and re-query rather than re-send. Sending twice
            // pays twice.
            log.error(
                    "CyberPay payout call failed for merchantRef={}: {}",
                    instruction.merchantReference(),
                    e.toString());
            throw ApiException.gatewayUnavailable(
                    "Could not reach the payout gateway for " + instruction.merchantReference());
        }

        return interpret(response, instruction.merchantReference());
    }

    /**
     * Reads their answer. Success is {@code Succeeded} true <em>and</em> a reference in {@code
     * Data} — a "succeeded" with nothing to re-query later is not something to mark a payout
     * PROCESSING on, because there would then be no way to ever find out what happened to it.
     */
    private PayoutDispatch interpret(JsonNode response, String merchantReference) {
        if (response == null) {
            return PayoutDispatch.refused("The payout gateway returned an empty response");
        }

        JsonNode succeeded = firstPresent(response, "Succeeded", "succeeded");
        JsonNode codeNode = firstPresent(response, "Code", "code");
        JsonNode messageNode = firstPresent(response, "Message", "message");
        JsonNode dataNode = firstPresent(response, "Data", "data");

        String code = codeNode == null ? null : codeNode.asString(null);
        String message = messageNode == null ? null : messageNode.asString(null);
        // Data is a bare string on this endpoint, not an object — the transfer's reference.
        String reference = dataNode == null ? null : dataNode.asString(null);

        boolean ok = succeeded != null && succeeded.asBoolean(false);
        if (ok && reference != null && !reference.isBlank()) {
            log.info(
                    "CyberPay accepted payout merchantRef={} as reference={} (code={})",
                    merchantReference,
                    reference,
                    code);
            return PayoutDispatch.accepted(reference);
        }

        log.warn(
                "CyberPay refused payout merchantRef={}: code={} message={}",
                merchantReference,
                code,
                message);
        if (ok) {
            // Succeeded with no reference. Refuse it: an accepted-but-untraceable payout is worse
            // than a refused one, because only the refusal can be safely retried.
            return PayoutDispatch.refused(
                    "The payout gateway accepted the transfer but returned no reference");
        }
        return PayoutDispatch.refused(
                message == null || message.isBlank()
                        ? "The payout gateway refused the transfer"
                                + (code == null ? "" : " (" + code + ")")
                        : message);
    }

    /** A cached bearer token, or a fresh one if there is none or it is about to expire. */
    private String bearerToken() {
        CachedToken cached = token.get();
        if (cached != null && cached.usableAt(Instant.now())) {
            return cached.value();
        }

        JsonNode body;
        try {
            body =
                    authClient
                            .post()
                            .uri(
                                    uri ->
                                            uri.path("/oauth2/token")
                                                    .queryParam("grant_type", "client_credentials")
                                                    .queryParam("scopes", "profile")
                                                    .build())
                            .headers(
                                    headers ->
                                            headers.setBasicAuth(
                                                    config.getClientId(), config.getClientSecret()))
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientException e) {
            // Deliberately does not log the exception body: a failed token exchange can echo the
            // credentials back in its error payload.
            log.error("CyberPay payout token request failed: {}", e.getClass().getSimpleName());
            throw ApiException.gatewayUnavailable("Could not authenticate with the payout gateway");
        }

        String accessToken = body == null ? null : body.path("access_token").asString(null);
        if (accessToken == null || accessToken.isBlank()) {
            log.error("CyberPay payout token response carried no access_token");
            throw ApiException.gatewayUnavailable("The payout gateway issued no access token");
        }

        long expiresIn = body.path("expires_in").asLong(3600L);
        CachedToken fresh =
                new CachedToken(
                        accessToken,
                        Instant.now().plusSeconds(expiresIn).minus(TOKEN_REFRESH_MARGIN));
        token.set(fresh);
        return fresh.value();
    }

    /** First of these field names that is actually present, or null. */
    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode found = node.get(name);
            if (found != null && !found.isNull()) {
                return found;
            }
        }
        return null;
    }

    /**
     * Naira to CyberPay's integer kobo. {@link RoundingMode#UNNECESSARY} is an assertion: a payout
     * amount is a two-decimal balance figure, so shifting it two places must land exactly. Throwing
     * beats quietly sending a rounded amount that no longer matches what the ledger reserved.
     */
    private static long toKobo(BigDecimal naira) {
        return naira.movePointRight(KOBO_PER_NAIRA_EXPONENT)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    private record CachedToken(String value, Instant usableUntil) {
        boolean usableAt(Instant now) {
            return now.isBefore(usableUntil);
        }
    }
}
