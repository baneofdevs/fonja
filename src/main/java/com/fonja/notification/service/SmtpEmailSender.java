package com.fonja.notification.service;

import com.fonja.notification.config.NotificationConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Plain SMTP with STARTTLS — Gmail at launch, any provider that speaks SMTP later.
 *
 * <p>Gmail specifics worth knowing: the account needs 2-step verification and an App Password (the
 * normal password is rejected), the From address has to be the account itself or one of its
 * verified "send mail as" aliases, and a personal account is capped at roughly 500 messages a day.
 * That cap is fine for launch and is the first thing to outgrow.
 */
@Service
@EnableConfigurationProperties(NotificationConfig.class)
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final NotificationConfig config;
    private final JavaMailSender mailSender;

    public SmtpEmailSender(NotificationConfig config) {
        this.config = config;
        this.mailSender = config.isConfigured() ? build(config) : null;

        if (mailSender == null) {
            // One line at startup, rather than a stack trace per notification. Every domain event
            // still fires and is logged as SKIPPED, so nothing is lost but the email itself.
            log.warn(
                    "Email notifications are disabled: FONJA_SMTP_HOST, FONJA_SMTP_USERNAME and "
                            + "FONJA_SMTP_PASSWORD are not all set. Notifications will be logged as "
                            + "SKIPPED.");
        } else {
            log.info(
                    "Email notifications enabled via {}:{} as {}",
                    config.getSmtpHost(),
                    config.getSmtpPort(),
                    config.effectiveFromAddress());
        }
    }

    @Override
    public boolean isAvailable() {
        return mailSender != null;
    }

    @Override
    public void send(
            String toAddress, String toName, String subject, String textBody, String htmlBody) {
        if (mailSender == null) {
            throw new IllegalStateException("SMTP is not configured");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(config.effectiveFromAddress(), config.getFromName());
            helper.setTo(
                    toName == null || toName.isBlank() ? toAddress : nameAddr(toAddress, toName));
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // The provider's message names the real cause — bad App Password, throttled, address
            // rejected — and is what ops needs to see in the log row.
            throw new EmailDeliveryException(e.getMessage(), e);
        }
    }

    private static String nameAddr(String address, String name) {
        // RFC 5322 display name; quotes keep a comma in "Surname, Name" from splitting the address.
        return "\"" + name.replace("\"", "") + "\" <" + address + ">";
    }

    private static JavaMailSender build(NotificationConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getSmtpHost());
        sender.setPort(config.getSmtpPort());
        sender.setUsername(config.getSmtpUsername());
        sender.setPassword(config.getSmtpPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        String timeout = String.valueOf(config.getTimeoutMillis());
        props.put("mail.smtp.connectiontimeout", timeout);
        props.put("mail.smtp.timeout", timeout);
        props.put("mail.smtp.writetimeout", timeout);
        return sender;
    }
}
