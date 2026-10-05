package com.fonja.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound email. Bound from {@code fonja.notification.*}; see {@code application.yml} and {@code
 * .env.example} for the Gmail values.
 *
 * <p>Not Spring Boot's own {@code spring.mail.*}: its auto-configuration builds a sender the moment
 * {@code spring.mail.host} exists, even as an empty string, and a sender with no host fails on
 * first use rather than at startup. Owning the properties lets {@link #isConfigured()} decide up
 * front, the same way {@code PayoutConfig} does for CyberPay.
 */
@ConfigurationProperties(prefix = "fonja.notification")
public class NotificationConfig {

    /** SMTP host, e.g. {@code smtp.gmail.com}. Blank disables sending entirely. */
    private String smtpHost = "";

    /** 587 is STARTTLS, which is what Gmail wants; 465 would be implicit TLS. */
    private int smtpPort = 587;

    /** For Gmail this is the full address, and the password is an App Password, not the login. */
    private String smtpUsername = "";

    private String smtpPassword = "";

    /** The From address. Defaults to the SMTP username, which is what Gmail insists on anyway. */
    private String fromAddress = "";

    private String fromName = "Fonja";

    /**
     * Where the frontend lives, e.g. {@code https://fonja.ng}, for the links in each email. Blank
     * means the emails carry no links.
     */
    private String appBaseUrl = "";

    /** Socket connect/read/write timeout — a hung SMTP session must not pin a worker thread. */
    private int timeoutMillis = 10_000;

    public boolean isConfigured() {
        return !smtpHost.isBlank() && !smtpUsername.isBlank() && !smtpPassword.isBlank();
    }

    /** The effective From address. */
    public String effectiveFromAddress() {
        return fromAddress.isBlank() ? smtpUsername : fromAddress;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(int smtpPort) {
        this.smtpPort = smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public void setSmtpPassword(String smtpPassword) {
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress == null ? "" : fromAddress.trim();
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl == null ? "" : appBaseUrl.trim().replaceAll("/+$", "");
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
