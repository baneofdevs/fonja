package com.fonja.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fonja.common.exception.ApiException;
import com.fonja.common.response.ResponseCode;
import com.fonja.common.security.JwtService;
import com.fonja.notification.config.NotificationConfig;
import com.fonja.notification.event.NotificationEvent;
import com.fonja.notification.service.EmailSender;
import com.fonja.user.dto.CodeSentResponse;
import com.fonja.user.dto.LoginRequest;
import com.fonja.user.dto.LoginResponse;
import com.fonja.user.dto.ResetPasswordRequest;
import com.fonja.user.dto.SignupRequest;
import com.fonja.user.dto.VerifyEmailRequest;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.AuthToken;
import com.fonja.user.entity.AuthTokenPurpose;
import com.fonja.user.entity.User;
import com.fonja.user.repository.AuthTokenRepository;
import com.fonja.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long USER_ID = 7L;

    @Mock private UserRepository userRepository;
    @Mock private AuthTokenRepository tokenRepository;
    @Mock private JwtService jwtService;
    @Mock private ApplicationEventPublisher events;
    @Mock private EmailSender emailSender;

    // Real, at the lowest cost factor: the tests are about what is hashed and compared, and a
    // mock encoder would let a plaintext password through unnoticed.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        NotificationConfig config = new NotificationConfig();
        config.setAppBaseUrl("https://fonja.test/");
        authService =
                new AuthService(
                        userRepository,
                        tokenRepository,
                        passwordEncoder,
                        jwtService,
                        events,
                        emailSender,
                        config);
        lenient().when(emailSender.isAvailable()).thenReturn(true);
        lenient()
                .when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            ReflectionTestUtils.setField(u, "id", USER_ID);
                            return u;
                        });
        lenient().when(jwtService.issueToken(any())).thenReturn("jwt");
    }

    private static SignupRequest signup(String email) {
        return new SignupRequest("Ada", "Obi", email, "0803 123 4567", "correct horse", true);
    }

    private User emailUser(boolean verified) {
        User user =
                User.withPassword(
                        "ada@example.com",
                        "Ada",
                        "Obi",
                        "+2348031234567",
                        passwordEncoder.encode("correct horse"));
        ReflectionTestUtils.setField(user, "id", USER_ID);
        if (verified) {
            user.markEmailVerified();
        }
        return user;
    }

    private AuthToken codeToken(User user, String code, Instant expiresAt) {
        return new AuthToken(
                user, AuthTokenPurpose.EMAIL_VERIFICATION, AuthService.sha256(code), expiresAt);
    }

    private String sentCode() {
        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(NotificationEvent.EmailVerificationCode.class);
        return ((NotificationEvent.EmailVerificationCode) event.getValue()).code();
    }

    // --- sign-up -------------------------------------------------------------------------

    @Test
    void signupCreatesAnUnverifiedAccountAndEmailsACode() {
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.empty());

        CodeSentResponse response = authService.signup(signup("  Ada@Example.com "));

        assertThat(response.email()).isEqualTo("ada@example.com");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.EMAIL);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getPhone()).isEqualTo("+2348031234567");
        assertThat(user.getFullName()).isEqualTo("Ada Obi");
        assertThat(user.getPasswordHash()).isNotEqualTo("correct horse");
        assertThat(passwordEncoder.matches("correct horse", user.getPasswordHash())).isTrue();

        String code = sentCode();
        assertThat(code).matches("\\d{6}");
        ArgumentCaptor<AuthToken> token = ArgumentCaptor.forClass(AuthToken.class);
        verify(tokenRepository).save(token.capture());
        assertThat(token.getValue().getTokenHash()).isEqualTo(AuthService.sha256(code));
        assertThat(token.getValue().getTokenHash()).doesNotContain(code);
    }

    @Test
    void anInvalidPhoneIsRefusedBeforeAnythingIsSaved() {
        SignupRequest request =
                new SignupRequest("Ada", "Obi", "ada@example.com", "12345", "correct horse", true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("phone number");
        verify(userRepository, never()).save(any());
    }

    @Test
    void signingUpWithAGoogleAccountsEmailIsAConflict() {
        when(userRepository.findByEmail("ada@example.com"))
                .thenReturn(
                        Optional.of(
                                new User("ada@example.com", "Ada Obi", AuthProvider.GOOGLE, "s")));

        assertThatThrownBy(() -> authService.signup(signup("ada@example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Google")
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }

    @Test
    void signingUpWithAVerifiedEmailIsAConflict() {
        when(userRepository.findByEmail("ada@example.com"))
                .thenReturn(Optional.of(emailUser(true)));

        assertThatThrownBy(() -> authService.signup(signup("ada@example.com")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.CONFLICT);
    }

    @Test
    void signingUpAgainBeforeVerifyingReplacesTheAbandonedAttempt() {
        User abandoned = emailUser(false);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(abandoned));

        authService.signup(
                new SignupRequest(
                        "Adaeze", "Obi", "ada@example.com", "08099998888", "new password", true));

        verify(userRepository, never()).save(any());
        assertThat(abandoned.getFirstName()).isEqualTo("Adaeze");
        assertThat(passwordEncoder.matches("new password", abandoned.getPasswordHash())).isTrue();
        verify(tokenRepository)
                .consumeAllOpen(eq(USER_ID), eq(AuthTokenPurpose.EMAIL_VERIFICATION), any());
        sentCode();
    }

    // --- verification --------------------------------------------------------------------

    @Test
    void theRightCodeVerifiesTheEmailAndSignsIn() {
        User user = emailUser(false);
        AuthToken token = codeToken(user, "482913", Instant.now().plusSeconds(600));
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        USER_ID, AuthTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        LoginResponse response =
                authService.verifyEmail(new VerifyEmailRequest("ada@example.com", "482913"));

        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.user().emailVerified()).isTrue();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(token.getConsumedAt()).isNotNull();
    }

    @Test
    void aWrongCodeCountsAnAttemptAndSaysHowManyAreLeft() {
        User user = emailUser(false);
        AuthToken token = codeToken(user, "482913", Instant.now().plusSeconds(600));
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        USER_ID, AuthTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(
                        () ->
                                authService.verifyEmail(
                                        new VerifyEmailRequest("ada@example.com", "000000")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4 attempt(s) left");
        assertThat(token.getAttempts()).isEqualTo(1);
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void afterTheLastAttemptEvenTheRightCodeIsRefused() {
        User user = emailUser(false);
        AuthToken token = codeToken(user, "482913", Instant.now().plusSeconds(600));
        for (int i = 0; i < AuthService.CODE_MAX_ATTEMPTS; i++) {
            token.recordFailedAttempt();
        }
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        USER_ID, AuthTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(
                        () ->
                                authService.verifyEmail(
                                        new VerifyEmailRequest("ada@example.com", "482913")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void anExpiredCodeIsRefused() {
        User user = emailUser(false);
        AuthToken token = codeToken(user, "482913", Instant.now().minusSeconds(1));
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        USER_ID, AuthTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(
                        () ->
                                authService.verifyEmail(
                                        new VerifyEmailRequest("ada@example.com", "482913")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aResendWithinTheCooldownSendsNothing() {
        User user = emailUser(false);
        AuthToken recent = codeToken(user, "111111", Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(recent, "createdAt", Instant.now().minusSeconds(10));
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        USER_ID, AuthTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(recent));

        authService.resendCode("ada@example.com");

        verify(events, never()).publishEvent(any(NotificationEvent.class));
    }

    @Test
    void aResendForAnUnknownEmailLooksTheSameAndSendsNothing() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        CodeSentResponse response = authService.resendCode("nobody@example.com");

        assertThat(response.email()).isEqualTo("nobody@example.com");
        verify(events, never()).publishEvent(any(NotificationEvent.class));
    }

    // --- login -----------------------------------------------------------------------------

    @Test
    void anUnknownEmailAndAWrongPasswordGetTheSameAnswer() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@example.com"))
                .thenReturn(Optional.of(emailUser(true)));

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "x")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.BAD_CREDENTIALS);
        assertThatThrownBy(
                        () -> authService.login(new LoginRequest("ada@example.com", "wrong one")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.BAD_CREDENTIALS)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.UNAUTHORIZED);
    }

    @Test
    void aGoogleAccountWithoutAPasswordCannotLogInWithOne() {
        when(userRepository.findByEmail("ada@example.com"))
                .thenReturn(
                        Optional.of(
                                new User("ada@example.com", "Ada Obi", AuthProvider.GOOGLE, "s")));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ada@example.com", "")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.BAD_CREDENTIALS);
    }

    @Test
    void anUnverifiedLoginGetsTheNotVerifiedCodeAndAFreshEmail() {
        when(userRepository.findByEmail("ada@example.com"))
                .thenReturn(Optional.of(emailUser(false)));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        anyLong(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                authService.login(
                                        new LoginRequest("ada@example.com", "correct horse")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getResponseCode())
                .isEqualTo(ResponseCode.EMAIL_NOT_VERIFIED);
        sentCode();
        verify(jwtService, never()).issueToken(any());
    }

    @Test
    void theRightPasswordSignsIn() {
        when(userRepository.findByEmail("ada@example.com"))
                .thenReturn(Optional.of(emailUser(true)));

        LoginResponse response =
                authService.login(new LoginRequest("ADA@example.com ", "correct horse"));

        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.user().email()).isEqualTo("ada@example.com");
    }

    // --- password reset --------------------------------------------------------------------

    @Test
    void forgotPasswordEmailsASingleUseLinkAndStoresOnlyItsHash() {
        User user = emailUser(true);
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(
                        USER_ID, AuthTokenPurpose.PASSWORD_RESET))
                .thenReturn(Optional.empty());

        authService.forgotPassword("ada@example.com");

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(events).publishEvent(event.capture());
        String url = ((NotificationEvent.PasswordResetLink) event.getValue()).resetUrl();
        assertThat(url).startsWith("https://fonja.test/reset-password?token=");
        String secret = url.substring(url.indexOf('=') + 1);

        ArgumentCaptor<AuthToken> token = ArgumentCaptor.forClass(AuthToken.class);
        verify(tokenRepository).save(token.capture());
        assertThat(token.getValue().getTokenHash()).isEqualTo(AuthService.sha256(secret));
    }

    @Test
    void forgotPasswordForAnUnknownEmailSendsNothing() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword("nobody@example.com");

        verify(events, never()).publishEvent(any(NotificationEvent.class));
    }

    @Test
    void aResetSetsTheNewPasswordVerifiesTheEmailAndRetiresTheLink() {
        User user = emailUser(false);
        AuthToken token =
                new AuthToken(
                        user,
                        AuthTokenPurpose.PASSWORD_RESET,
                        AuthService.sha256("secret"),
                        Instant.now().plusSeconds(600));
        when(tokenRepository.findByTokenHashAndPurpose(
                        AuthService.sha256("secret"), AuthTokenPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(token));

        authService.resetPassword(new ResetPasswordRequest("secret", "brand new pass"));

        assertThat(passwordEncoder.matches("brand new pass", user.getPasswordHash())).isTrue();
        assertThat(user.isEmailVerified()).isTrue();
        verify(tokenRepository)
                .consumeAllOpen(eq(USER_ID), eq(AuthTokenPurpose.PASSWORD_RESET), any());
    }

    @Test
    void aUsedResetLinkIsRefused() {
        User user = emailUser(true);
        AuthToken token =
                new AuthToken(
                        user,
                        AuthTokenPurpose.PASSWORD_RESET,
                        AuthService.sha256("secret"),
                        Instant.now().plusSeconds(600));
        token.consume(Instant.now());
        when(tokenRepository.findByTokenHashAndPurpose(any(), any()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(
                        () ->
                                authService.resetPassword(
                                        new ResetPasswordRequest("secret", "brand new pass")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid or has expired");
    }
}
