package com.fonja.ledger.entity;

/**
 * What a ledger entry records, and which way it moves the beneficiary's balance.
 *
 * <p>Amounts on {@link LedgerEntry} are always positive — direction lives here rather than in the
 * sign of the number, so a balance can never be flipped by a stray minus sign. Keeping the
 * direction on the enum also means the balance derivation has exactly one place to consult: add a
 * new type here and every sum picks it up automatically.
 *
 * <p>Stored as a string in {@code ledger_entries.entry_type}, so adding a constant needs no
 * migration. Never rename or remove one — historical rows carry the old name and the ledger is
 * append-only.
 */
public enum LedgerEntryType {

    /** The creator's 93% share of a confirmed donation. */
    DONATION_CREDIT(Direction.CREDIT),

    /**
     * Fonja's 7% share of a confirmed donation. Written with a null beneficiary, so it is platform
     * revenue and never lands in a creator's balance.
     */
    PLATFORM_FEE(Direction.CREDIT),

    /**
     * Earnings reserved by a withdrawal request, taken at request time rather than at settlement.
     */
    WITHDRAWAL_DEBIT(Direction.DEBIT),

    /** The flat withdrawal fee, taken alongside the debit. */
    WITHDRAWAL_FEE(Direction.DEBIT),

    /**
     * Releases a {@link #WITHDRAWAL_DEBIT} whose payout failed at the gateway. A compensating entry
     * rather than a deletion — the ledger is append-only, so a failed withdrawal stays visible as
     * the pair of rows that happened.
     */
    WITHDRAWAL_REVERSAL(Direction.CREDIT),

    /**
     * Releases a {@link #WITHDRAWAL_FEE} whose payout failed. Fonja does not charge for a failure.
     */
    WITHDRAWAL_FEE_REVERSAL(Direction.CREDIT);

    private final Direction direction;

    LedgerEntryType(Direction direction) {
        this.direction = direction;
    }

    public Direction getDirection() {
        return direction;
    }

    public boolean isCredit() {
        return direction == Direction.CREDIT;
    }

    /** Which way an entry moves the balance it belongs to. */
    public enum Direction {
        CREDIT,
        DEBIT
    }
}
