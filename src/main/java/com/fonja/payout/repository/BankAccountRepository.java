package com.fonja.payout.repository;

import com.fonja.payout.entity.BankAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    /** At most one per user — {@code uk_bank_accounts_user} makes a second one impossible. */
    Optional<BankAccount> findByUserId(Long userId);
}
