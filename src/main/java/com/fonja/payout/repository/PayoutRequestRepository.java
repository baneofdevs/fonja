package com.fonja.payout.repository;

import com.fonja.payout.entity.PayoutRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, Long> {}
