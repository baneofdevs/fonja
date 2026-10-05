package com.fonja.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fonja.campaign.entity.Category;
import com.fonja.campaign.service.CategoryService;
import com.fonja.common.exception.ApiException;
import com.fonja.common.util.PhoneNumbers;
import com.fonja.user.dto.ChangePasswordRequest;
import com.fonja.user.dto.OnboardingRequest;
import com.fonja.user.dto.UpdateProfileRequest;
import com.fonja.user.dto.UserResponse;
import com.fonja.user.entity.AccountIntent;
import com.fonja.user.entity.AuthProvider;
import com.fonja.user.entity.User;
import com.fonja.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final long USER_ID = 7L;

    @Mock private UserRepository userRepository;
    @Mock private CategoryService categoryService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private UserService userService;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, categoryService, passwordEncoder);
        user = new User("ada@example.com", "Ada", "Obi", AuthProvider.GOOGLE, "sub");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void googleSignInUsesTheGivenAndFamilyNameClaims() {
        when(userRepository.findByAuthProviderAndAuthProviderSub(AuthProvider.GOOGLE, "new"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("tunde@example.com")).thenReturn(Optional.empty());

        User created =
                userService.upsertFromOAuth2(
                        AuthProvider.GOOGLE,
                        "new",
                        "tunde@example.com",
                        "Tunde",
                        "Bello Ade",
                        "Tunde Bello Ade");

        assertThat(created.getFirstName()).isEqualTo("Tunde");
        assertThat(created.getLastName()).isEqualTo("Bello Ade");
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.isOnboarded()).isFalse();
    }

    @Test
    void withoutNameClaimsTheDisplayNameIsSplitAtTheFirstSpace() {
        when(userRepository.findByAuthProviderAndAuthProviderSub(AuthProvider.GOOGLE, "new"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("tunde@example.com")).thenReturn(Optional.empty());

        User created =
                userService.upsertFromOAuth2(
                        AuthProvider.GOOGLE, "new", "tunde@example.com", null, null, "Tunde");

        assertThat(created.getFirstName()).isEqualTo("Tunde");
        assertThat(created.getLastName()).isEmpty();
        assertThat(created.getFullName()).isEqualTo("Tunde");
    }

    @Test
    void aReturningGoogleUserKeepsTheNameTheyEditedInFonja() {
        user.setName("Adaeze", "Obi");
        when(userRepository.findByAuthProviderAndAuthProviderSub(AuthProvider.GOOGLE, "sub"))
                .thenReturn(Optional.of(user));

        userService.upsertFromOAuth2(
                AuthProvider.GOOGLE, "sub", "ada@example.com", "Ada", "Obi", "Ada Obi");

        assertThat(user.getFirstName()).isEqualTo("Adaeze");
    }

    @Test
    void onboardingStoresTheIntentAndInterestsAndMarksTheUserOnboarded() {
        Category education = new Category("education", "Education", null, 10);
        when(categoryService.requireAssignable(List.of("education")))
                .thenReturn(List.of(education));

        UserResponse response =
                userService.completeOnboarding(
                        USER_ID, new OnboardingRequest(AccountIntent.BOTH, List.of("education")));

        assertThat(response.onboarded()).isTrue();
        assertThat(response.accountIntent()).isEqualTo(AccountIntent.BOTH);
        assertThat(response.interests()).extracting("slug").containsExactly("education");
    }

    @Test
    void profileUpdatesNormalizeThePhoneAndClearBlankFields() {
        user.setBio("Old bio");

        UserResponse response =
                userService.updateProfile(
                        USER_ID,
                        new UpdateProfileRequest(
                                " Adaeze ", null, "0803-123-4567", "  ", "Lagos", null));

        assertThat(response.firstName()).isEqualTo("Adaeze");
        assertThat(response.lastName()).isEqualTo("Obi");
        assertThat(response.phone()).isEqualTo("+2348031234567");
        assertThat(response.bio()).isNull();
        assertThat(response.location()).isEqualTo("Lagos");
    }

    @Test
    void aGoogleAccountCanSetAFirstPasswordWithoutACurrentOne() {
        userService.changePassword(USER_ID, new ChangePasswordRequest(null, "first password"));

        assertThat(passwordEncoder.matches("first password", user.getPasswordHash())).isTrue();
    }

    @Test
    void changingAnExistingPasswordNeedsTheCurrentOne() {
        user.setPasswordHash(passwordEncoder.encode("old password"));

        assertThatThrownBy(
                        () ->
                                userService.changePassword(
                                        USER_ID,
                                        new ChangePasswordRequest("guess", "new password")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("current password");

        userService.changePassword(
                USER_ID, new ChangePasswordRequest("old password", "new password"));
        assertThat(passwordEncoder.matches("new password", user.getPasswordHash())).isTrue();
    }

    @Test
    void phoneNumbersAreStoredInE164() {
        assertThat(PhoneNumbers.normalizeNigerian("08031234567")).isEqualTo("+2348031234567");
        assertThat(PhoneNumbers.normalizeNigerian("803 123 4567")).isEqualTo("+2348031234567");
        assertThat(PhoneNumbers.normalizeNigerian("+234 803 123 4567")).isEqualTo("+2348031234567");
        assertThat(PhoneNumbers.normalizeNigerian("2349031234567")).isEqualTo("+2349031234567");
        assertThatThrownBy(() -> PhoneNumbers.normalizeNigerian("0603123456"))
                .isInstanceOf(ApiException.class);
    }
}
