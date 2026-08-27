package com.fonja.donation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CyberPay connection settings, bound from {@code fonja.cyberpay.*}.
 *
 * <p>Both secrets default to empty rather than to a placeholder. Nothing here stops the app from
 * starting — a developer working on the campaign module should not be blocked by payment
 * credentials — but {@code CyberPaySignatureVerifier} refuses every webhook while the integration
 * key is blank, and says why. Failing closed on an unset key is the whole point: a blank key would
 * otherwise verify a signature computed with a blank key, which anyone can compute.
 */
@ConfigurationProperties(prefix = "fonja.cyberpay")
public class CyberPayConfig {

    private String baseUrl = "https://payment-api.staging.cyberpay.ng";

    /**
     * Doubles as the webhook signing key: CyberPay HMACs each notification with the same
     * integration key we send when initiating a payment. Never log this.
     */
    private String integrationKey = "";

    /** Sent as the {@code ApiKey} header when initiating a payment. */
    private String apiKey = "";

    /**
     * Where CyberPay posts payment notifications. Sent per transaction rather than configured once
     * in their dashboard, so each environment gets its own without any shared setup.
     *
     * <p>Must be reachable from the public internet — {@code localhost} is not. In development that
     * means a tunnel (ngrok or similar); without one, checkout still works and the webhook simply
     * never arrives, leaving the donation PENDING.
     */
    private String webhookUrl = "";

    /** Where CyberPay sends the donor's browser after checkout, successful or not. */
    private String returnUrl = "";

    /**
     * Bounded on purpose. The webhook handler re-queries CyberPay while holding a request thread,
     * so an unbounded read here would let a slow gateway exhaust the pool. Timing out means a 503,
     * which makes CyberPay redeliver in an hour — the failure is safe.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(15);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getIntegrationKey() {
        return integrationKey;
    }

    public void setIntegrationKey(String integrationKey) {
        this.integrationKey = integrationKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public void setReturnUrl(String returnUrl) {
        this.returnUrl = returnUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
