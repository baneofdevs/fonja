package com.fonja.donation.dto;

import java.math.BigDecimal;

/** Sum and count over some set of donations. Built by a JPQL constructor expression. */
public record DonationTotals(BigDecimal amount, long count) {}
