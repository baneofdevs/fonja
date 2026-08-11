package com.fonja.user.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    private AuthProvider authProvider;

    @Column(name = "auth_provider_sub", unique = true)
    private String authProviderSub;

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
     * Creates a user from an OAuth2 login. Everyone starts as a {@link Role#DONOR}:
     * donating requires only a registered account, and {@link Role#CREATOR} is
     * granted later by verification approval (technical spec §5.4).
     */
    public User(String email, String fullName, AuthProvider authProvider, String authProviderSub) {
        this.email = email;
        this.fullName = fullName;
        this.authProvider = authProvider;
        this.authProviderSub = authProviderSub;
        this.roles.add(Role.DONOR);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public void grantRole(Role role) {
        this.roles.add(role);
    }

    /**
     * Called only by the verification module on admin approval. Verification is
     * one-time and permanent — an already-verified creator skips the gate on
     * every future campaign Launch (technical spec §4.2).
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

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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
