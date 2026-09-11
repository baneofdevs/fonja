package com.fonja.payout.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CyberPay's PayOut API is a different service from the one that takes donations, with a different
 * base URL, different credentials and a different auth scheme — OAuth2 {@code client_credentials}
 * bearer tokens rather than the {@code ApiKey} header collections use. Hence its own properties
 * block instead of extra fields on {@code CyberPayConfig}.
 *
 * <p><strong>Disabled by default, and deliberately.</strong> Everything up to the transfer works
 * without it: a creator links an account, requests a withdrawal, and the ledger reserves the money.
 * Only the instruction to CyberPay needs these values, and until they are set {@code
 * CyberPayPayoutClient} refuses to pretend — a payout stays REQUESTED, which is the state that says
 * "reserved, not sent", and an admin can settle it by hand.
 */
@ConfigurationProperties(prefix = "fonja.payout")
public class PayoutConfig {

    /** Base URL of the PayOut service. Blank disables automatic disbursement entirely. */
    private String baseUrl = "";

    /** Where {@code POST /oauth2/token} lives. A different host from {@link #baseUrl}. */
    private String authorizationUrl = "";

    private String clientId = "";

    /** Never log this. Sent as the password half of HTTP Basic on the token call. */
    private String clientSecret = "";

    /**
     * The wallet a payout is debited from. CyberPay identifies it by an opaque GUID rather than an
     * account number, and it is the one value here that cannot be guessed or derived — it has to
     * come from them.
     */
    private String walletCode = "";

    /** {@code Business} or {@code Customer}. Fonja's collections settle into a business wallet. */
    private String walletType = "Business";

    /**
     * What appears on the creator's bank statement. Nigerian banks truncate hard, so keep it short
     * and recognisable rather than descriptive.
     */
    private String senderName = "FONJA";

    /**
     * Guard rail on automatic disbursement: anything above this is left REQUESTED for a human. Zero
     * means no ceiling. A bug that drains a wallet does so one instruction at a time, and this is
     * what makes each one small.
     */
    private BigDecimal maxAutomaticAmount = new BigDecimal("200000.00");

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Longer than the collections read timeout. A transfer instruction is not idempotent from our
     * side — a timeout leaves us unsure whether CyberPay accepted it — so it is worth waiting
     * rather than giving up early and having to reconcile.
     */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** True when there is enough here to actually send an instruction. */
    public boolean isConfigured() {
        return !baseUrl.isBlank()
                && !authorizationUrl.isBlank()
                && !clientId.isBlank()
                && !clientSecret.isBlank()
                && !walletCode.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public void setAuthorizationUrl(String authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getWalletCode() {
        return walletCode;
    }

    public void setWalletCode(String walletCode) {
        this.walletCode = walletCode;
    }

    public String getWalletType() {
        return walletType;
    }

    public void setWalletType(String walletType) {
        this.walletType = walletType;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public BigDecimal getMaxAutomaticAmount() {
        return maxAutomaticAmount;
    }

    public void setMaxAutomaticAmount(BigDecimal maxAutomaticAmount) {
        this.maxAutomaticAmount = maxAutomaticAmount;
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
