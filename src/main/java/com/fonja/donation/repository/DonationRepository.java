package com.fonja.donation.repository;

import com.fonja.donation.entity.Donation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DonationRepository extends JpaRepository<Donation, Long> {
    // TODO: findByGatewayReference for idempotency checks
}
