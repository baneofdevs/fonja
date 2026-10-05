package com.fonja.user.service;

import com.fonja.common.exception.ApiException;
import com.fonja.common.security.JwtService;
import com.fonja.common.util.PhoneNumbers;
import com.fonja.notification.config.NotificationConfig;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.event.Recipient;
import com.fonja.notification.service.EmailSender;
import com.fonja.user.dto.CodeSentResponse;
import com.fonja.user.dto.LoginRequest;
import com.fonja.user.dto.LoginResponse;
import com.fonja.user.dto.ResetPasswordRequest;
import com.fonja.user.dto.SignupRequest;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.dto.VerifyEmailRequest;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.AuthToken;
import com.fonja.user.entity.AuthTokenPurpose;
import com.fonja.user.entity.User;
import com.fonja.user.repository.AuthTokenRepository;
import com.fonja.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email-and-password accounts: sign-up, the emailed six-digit code that proves the address, login,
 * and password reset. Google sign-in stays in {@code OAuth2LoginSuccessHandler}; both end in the
 * same {@link LoginResponse}.
 *
 * <p>No token is issued until the address is proved, so every JWT in circulation belongs to a user
 * whose email is verified — nothing downstream has to check.
 *
 * <p>Several methods are {@code noRollbackFor = ApiException.class}: a wrong code must still count
 * as an attempt, and a login refused for an unverified email must still send the fresh code, even
 * though the request itself fails.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(15);
    static final int CODE_MAX_ATTEMPTS = 5;
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Same message for an unknown email and a wrong password, so neither reveals the other. */
    static final String BAD_CREDENTIALS = "Incorrect email or password";

    private final UserRepository userRepository;
    private final AuthTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;
    private final EmailSender emailSender;
    private final NotificationConfig notificationConfig;
    private final SecureRandom random = new SecureRandom();

    /**
     * A real BCrypt hash of nothing in particular. Login checks against it when the email is
     * unknown, so the response takes as long as a wrong password and timing does not reveal which
     * addresses have accounts.
     */
    private final String timingDecoyHash;

    public AuthService(
            UserRepository userRepository,
            AuthTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            ApplicationEventPublisher events,
            EmailSender emailSender,
            NotificationConfig notificationConfig) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.events = events;
        this.emailSender = emailSender;
        this.notificationConfig = notificationConfig;
        this.timingDecoyHash = passwordEncoder.encode("fonja-timing-decoy");
    }

    /**
     * Creates an unverified account and emails a code. Signing up again with the address of an
     * account that never finished verifying replaces that attempt — nobody proved they own it, so
     * there is nothing to protect, and the person at the keyboard now can finish.
     */
    @Transactional
    public CodeSentResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        String phone = PhoneNumbers.normalizeNigerian(request.phone());
        String firstName = request.firstName().trim();
        String lastName = request.lastName().trim();
        String hash = passwordEncoder.encode(request.password());

        Optional<User> existing = userRepository.findByEmail(email);
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (user.getAuthProvider() != AuthProvider.EMAIL) {
                throw ApiException.conflict(
                        "This email is registered with Google. Continue with Google instead.");
            }
            if (user.isEmailVerified()) {
                throw ApiException.conflict(
                        "An account with this email already exists. Log in instead.");
            }
            user.setName(firstName, lastName);
            user.setPhone(phone);
            user.setPasswordHash(hash);
        } else {
            user = userRepository.save(User.withPassword(email, firstName, lastName, phone, hash));
            log.info("Created user id={} via provider=EMAIL", user.getId());
        }

        issueVerificationCode(user, Instant.now());
        return new CodeSentResponse(email, (int) RESEND_COOLDOWN.toSeconds());
    }

    /** Checks the code against the newest one sent; on success the user is signed in. */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse verifyEmail(VerifyEmailRequest request) {
        User user =
                userRepository
                        .findByEmail(normalizeEmail(request.email()))
                        .orElseThrow(() -> ApiException.badRequest("That code is incorrect"));
        if (user.isEmailVerified()) {
            throw ApiException.conflict("This email is already verified. Log in instead.");
        }

        Instant now = Instant.now();
        AuthToken token =
                tokenRepository
                        .findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                                user.getId(), AuthTokenPurpose.EMAIL_VERIFICATION)
                        .filter(t -> t.isUsable(now, CODE_MAX_ATTEMPTS))
                        .orElseThrow(
                                () ->
                                        ApiException.badRequest(
                                                "That code has expired. Request a new one."));

        if (!constantTimeEquals(token.getTokenHash(), sha256(request.code()))) {
            token.recordFailedAttempt();
            int left = CODE_MAX_ATTEMPTS - token.getAttempts();
            throw ApiException.badRequest(
                    left > 0
                            ? "That code is incorrect. " + left + " attempt(s) left."
                            : "Too many incorrect attempts. Request a new code.");
        }

        token.consume(now);
        user.markEmailVerified();
        log.info("User id={} verified their email", user.getId());
        return signIn(user);
    }

    /**
     * Sends a fresh code, at most once a minute. Answers the same way for an unknown or already
     * verified address so it cannot be used to discover accounts.
     */
    @Transactional
    public CodeSentResponse resendCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        userRepository
                .findByEmail(email)
                .filter(u -> u.getAuthProvider() == AuthProvider.EMAIL && !u.isEmailVerified())
                .ifPresent(u -> issueVerificationCodeUnlessRecent(u, Instant.now()));
        return new CodeSentResponse(email, (int) RESEND_COOLDOWN.toSeconds());
    }

    /**
     * Any account with a password can log in with it — including a Google account that set one. An
     * unverified account gets a fresh code and the {@code 07} error, which tells the client to go
     * to the verification screen.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmail(normalizeEmail(request.email()));
        if (found.isEmpty() || !found.get().hasPassword()) {
            passwordEncoder.matches(request.password(), timingDecoyHash);
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }
        if (!user.isEmailVerified()) {
            issueVerificationCodeUnlessRecent(user, Instant.now());
            throw ApiException.emailNotVerified(
                    "Verify your email to continue. We've sent a code to " + user.getEmail() + ".");
        }
        log.info("Password login succeeded for user id={}", user.getId());
        return signIn(user);
    }

    /**
     * Emails a reset link if the address has an account. Always answers the same way. Works for a
     * Google account too: proving the inbox is proof enough to add a password.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        Instant now = Instant.now();
        userRepository
                .findByEmail(normalizeEmail(rawEmail))
                .filter(u -> !sentRecently(u, AuthTokenPurpose.PASSWORD_RESET, now))
                .ifPresent(
                        user -> {
                            tokenRepository.consumeAllOpen(
                                    user.getId(), AuthTokenPurpose.PASSWORD_RESET, now);
                            String secret = randomUrlToken();
                            tokenRepository.save(
                                    new AuthToken(
                                            user,
                                            AuthTokenPurpose.PASSWORD_RESET,
                                            sha256(secret),
                                            now.plus(RESET_TTL)));
                            String url = resetUrl(secret);
                            if (!emailSender.isAvailable()) {
                                log.warn(
                                        "Email is not configured; password reset link for user"
                                                + " id={}: {}",
                                        user.getId(),
                                        url);
                            }
                            events.publishEvent(
                                    new NotificationEvent.PasswordResetLink(
                                            Recipient.of(user), url, (int) RESET_TTL.toMinutes()));
                        });
    }

    /**
     * Sets a new password from a reset link. The link is single-use, and following it proves the
     * inbox, so it also completes an unfinished email verification.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = Instant.now();
        AuthToken token =
                tokenRepository
                        .findByTokenHashAndPurpose(
                                sha256(request.token()), AuthTokenPurpose.PASSWORD_RESET)
                        .filter(t -> t.isUsable(now, Integer.MAX_VALUE))
                        .orElseThrow(
                                () ->
                                        ApiException.badRequest(
                                                "This reset link is invalid or has expired."
                                                        + " Request a new one."));
        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.markEmailVerified();
        tokenRepository.consumeAllOpen(user.getId(), AuthTokenPurpose.PASSWORD_RESET, now);
        log.info("User id={} reset their password", user.getId());
    }

    // --- internals -----------------------------------------------------------------------

    private LoginResponse signIn(User user) {
        return new LoginResponse(jwtService.issueToken(user), UserResponse.from(user));
    }

    private void issueVerificationCodeUnlessRecent(User user, Instant now) {
        if (!sentRecently(user, AuthTokenPurpose.EMAIL_VERIFICATION, now)) {
            issueVerificationCode(user, now);
        }
    }

    private boolean sentRecently(User user, AuthTokenPurpose purpose, Instant now) {
        return tokenRepository
                .findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(user.getId(), purpose)
                .map(
                        t ->
                                t.getCreatedAt() != null
                                        && t.getCreatedAt().plus(RESEND_COOLDOWN).isAfter(now))
                .orElse(false);
    }

    private void issueVerificationCode(User user, Instant now) {
        tokenRepository.consumeAllOpen(user.getId(), AuthTokenPurpose.EMAIL_VERIFICATION, now);
        String code = String.format("%06d", random.nextInt(1_000_000));
        tokenRepository.save(
                new AuthToken(
                        user,
                        AuthTokenPurpose.EMAIL_VERIFICATION,
                        sha256(code),
                        now.plus(CODE_TTL)));
        if (!emailSender.isAvailable()) {
            // Development only in practice: without SMTP the code would go nowhere and nobody
            // could finish signing up.
            log.warn(
                    "Email is not configured; verification code for user id={} is {}",
                    user.getId(),
                    code);
        }
        events.publishEvent(
                new NotificationEvent.EmailVerificationCode(
                        Recipient.of(user), code, (int) CODE_TTL.toMinutes()));
    }

    private String resetUrl(String secret) {
        String base =
                notificationConfig.getAppBaseUrl() == null
                        ? ""
                        : notificationConfig.getAppBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/reset-password?token=" + secret;
    }

    private String randomUrlToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
