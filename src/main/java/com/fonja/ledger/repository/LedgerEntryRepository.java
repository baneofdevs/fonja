package com.fonja.ledger.repository;

import com.fonja.ledger.entity.LedgerEntry;
import com.fonja.ledger.entity.LedgerEntryType;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * Per-type totals for one creator, folded into a balance by {@code LedgerService}.
     *
     * <p>Grouping and summing in one query rather than issuing a credits query and a debits query
     * keeps the two halves of a balance on the same read — and keeps the knowledge of which types
     * are credits in {@link LedgerEntryType} instead of duplicating it into JPQL. Backed by {@code
     * idx_ledger_entries_beneficiary (beneficiary_id, entry_type)}.
     */
    @Query(
            """
            select e.entryType as entryType, sum(e.amount) as total
            from LedgerEntry e
            where e.beneficiary.id = :beneficiaryId
            group by e.entryType
            """)
    List<TypeTotal> sumByTypeForBeneficiary(@Param("beneficiaryId") Long beneficiaryId);

    /** Same fold, for the platform's own revenue — the entries written with no beneficiary. */
    @Query(
            """
            select e.entryType as entryType, sum(e.amount) as total
            from LedgerEntry e
            where e.beneficiary is null
            group by e.entryType
            """)
    List<TypeTotal> sumByTypeForPlatform();

    /**
     * Idempotency guard for the donation split. The unique index on {@code (donation_id,
     * entry_type)} makes a double write impossible at the database level; this lets the service
     * skip the attempt rather than catching the constraint violation, which would otherwise mark
     * the surrounding transaction rollback-only.
     */
    boolean existsByDonationId(Long donationId);

    /**
     * The same guard for withdrawals, checked per leg so a reversal can follow the original pair.
     */
    boolean existsByPayoutRequestIdAndEntryType(Long payoutRequestId, LedgerEntryType entryType);

    List<LedgerEntry> findByBeneficiaryIdOrderByCreatedAtDesc(Long beneficiaryId);

    /** Projection for the grouped sums above. */
    interface TypeTotal {
        LedgerEntryType getEntryType();

        BigDecimal getTotal();
    }
}
