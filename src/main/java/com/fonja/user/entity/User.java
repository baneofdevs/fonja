package com.fonja.user.entity;

import com.fonja.campaign.entity.Category;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    private AuthProvider authProvider;

    @Column(name = "auth_provider_sub", unique = true)
    private String authProviderSub;

    /** BCrypt. Null for an OAuth account that has never set a password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /**
     * Whether the user has proved they own {@link #email}. OAuth providers vouch for it; password
     * sign-ups prove it with a code. Not to be confused with {@link #verified}, which is creator
     * identity verification.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_intent", length = 20)
    private AccountIntent accountIntent;

    /** When the user finished the "how will you use Fonja" steps. Null until then. */
    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    /** A {@code campaign_media} row; served publicly at {@code /api/v1/media/{id}}. */
    @Column(name = "avatar_media_id")
    private Long avatarMediaId;

    @Column(length = 500)
    private String bio;

    @Column(length = 120)
    private String location;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_interests",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> interests = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<Role> roles = new HashSet<>();

    @Column(nullable = false)
    private boolean verified = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA
    }

    /**
     * Creates a user from an OAuth2 login. Everyone starts as a {@link Role#DONOR}: donating
     * requires only a registered account, and {@link Role#CREATOR} is granted later by verification
     * approval (technical spec §5.4). The provider has already checked the email address.
     */
    public User(
            String email,
            String firstName,
            String lastName,
            AuthProvider authProvider,
            String authProviderSub) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName == null ? "" : lastName;
        this.authProvider = authProvider;
        this.authProviderSub = authProviderSub;
        this.emailVerified = authProvider != AuthProvider.EMAIL;
        this.roles.add(Role.DONOR);
    }

    /** As above, from a single display name — split at the first space. */
    public User(String email, String fullName, AuthProvider authProvider, String authProviderSub) {
        this(email, firstOf(fullName), restOf(fullName), authProvider, authProviderSub);
    }

    /** A password sign-up. Unverified until the emailed code comes back. */
    public static User withPassword(
            String email, String firstName, String lastName, String phone, String passwordHash) {
        User user = new User(email, firstName, lastName, AuthProvider.EMAIL, null);
        user.phone = phone;
        user.passwordHash = passwordHash;
        return user;
    }

    static String firstOf(String fullName) {
        String trimmed = fullName == null ? "" : fullName.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    static String restOf(String fullName) {
        String trimmed = fullName == null ? "" : fullName.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? "" : trimmed.substring(space + 1).trim();
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public void grantRole(Role role) {
        this.roles.add(role);
    }

    /**
     * Called only by the verification module on admin approval. Verification is one-time and
     * permanent — an already-verified creator skips the gate on every future campaign Launch
     * (technical spec §4.2).
     */
    public void markVerified() {
        this.verified = true;
        grantRole(Role.CREATOR);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /** Derived: first and last name, or just the first when there is no last. */
    public String getFullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setName(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName == null ? "" : lastName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getAuthProviderSub() {
        return authProviderSub;
    }

    public void setAuthProviderSub(String authProviderSub) {
        this.authProviderSub = authProviderSub;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public AccountIntent getAccountIntent() {
        return accountIntent;
    }

    public void setAccountIntent(AccountIntent accountIntent) {
        this.accountIntent = accountIntent;
    }

    public Instant getOnboardedAt() {
        return onboardedAt;
    }

    public boolean isOnboarded() {
        return onboardedAt != null;
    }

    public void markOnboarded(Instant at) {
        if (onboardedAt == null) {
            onboardedAt = at;
        }
    }

    public Long getAvatarMediaId() {
        return avatarMediaId;
    }

    public void setAvatarMediaId(Long avatarMediaId) {
        this.avatarMediaId = avatarMediaId;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Set<Category> getInterests() {
        return Collections.unmodifiableSet(interests);
    }

    public void replaceInterests(Collection<Category> categories) {
        interests.clear();
        interests.addAll(categories);
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean isVerified() {
        return verified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
