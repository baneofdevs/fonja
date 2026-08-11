package com.fonja.payout.repository;

import com.fonja.payout.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    // TODO: findByUserId
}
