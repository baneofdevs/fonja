package com.fonja.ledger.repository;

import com.fonja.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    // TODO: sum-by-beneficiary query for available-earnings derivation
}
