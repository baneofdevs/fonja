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

    /**
     * Same fold, for Fonja's own revenue.
     *
     * <p>Selected by entry type rather than by "no beneficiary", which is the obvious query and the
     * wrong one. Both of Fonja's income streams are here, and they are shaped differently:
     * `PLATFORM_FEE` is written with a null beneficiary, but a withdrawal fee is written *against
     * the creator* — it has to be, or it would not come out of their balance. Filtering on a null
     * beneficiary would therefore count the 7% and silently miss every ₦200, understating revenue
     * by the whole withdrawal-fee line that BRD §227 counts on.
     *
     * <p>The same entries legitimately appear in both this sum and the creator's: a withdrawal fee
     * is a debit to them and income to Fonja. That is double-entry working correctly, not a bug.
     */
    @Query(
            """
            select e.entryType as entryType, sum(e.amount) as total
            from LedgerEntry e
            where e.entryType in :types
            group by e.entryType
            """)
    List<TypeTotal> sumByTypeForPlatform(@Param("types") List<LedgerEntryType> types);

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
