package com.fonja.payout.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "bank_code", nullable = false)
    private String bankCode;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected BankAccount() {
        // JPA
    }

    // TODO: constructor, getters/setters per team convention
}
