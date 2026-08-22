package com.fonja.ledger.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two numbers that define Fonja's cut, bound from {@code fonja.ledger.*}.
 *
 * <p>They are configuration rather than constants so a rate change is a deploy setting instead of a
 * code change — but note that changing either only affects entries written afterwards. Historical
 * ledger rows keep the split they were written with, which is the point of an append-only ledger.
 */
@ConfigurationProperties(prefix = "fonja.ledger")
public class LedgerConfig {

    /** Fonja's share of every donation, as a percentage. 7% per the BRD. */
    private BigDecimal platformFeePercent = new BigDecimal("7.00");

    /** Flat fee deducted from every withdrawal, in the creator's currency. ₦200 per the BRD. */
    private BigDecimal withdrawalFee = new BigDecimal("200.00");

    public BigDecimal getPlatformFeePercent() {
        return platformFeePercent;
    }

    public void setPlatformFeePercent(BigDecimal platformFeePercent) {
        this.platformFeePercent = platformFeePercent;
    }

    public BigDecimal getWithdrawalFee() {
        return withdrawalFee;
    }

    public void setWithdrawalFee(BigDecimal withdrawalFee) {
        this.withdrawalFee = withdrawalFee;
    }
}
