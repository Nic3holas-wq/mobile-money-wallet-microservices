package com.nicko.customer.repository;

import com.nicko.customer.entity.KycProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface KycProfileRepository extends JpaRepository<KycProfile, UUID> {
    Optional<KycProfile> findByCustomerId(UUID customerId);
}
