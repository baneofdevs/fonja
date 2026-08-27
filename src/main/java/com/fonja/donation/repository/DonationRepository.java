package com.fonja.donation.repository;

import com.fonja.donation.entity.Donation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DonationRepository extends JpaRepository<Donation, Long> {

    /**
     * Resolves a CyberPay notification back to the donation it belongs to. Backed by the unique
     * constraint {@code uk_donations_gateway_reference}, which is what makes a webhook retry find
     * the same row instead of creating a second one.
     */
    Optional<Donation> findByGatewayReference(String gatewayReference);
}
