package com.nicko.customer.repository;

import com.nicko.customer.entity.CustomerConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface CustomerConsentRepository extends JpaRepository<CustomerConsent, UUID> {
    Page<CustomerConsent> findByCustomerId(UUID customerId, Pageable pageable);
    Optional<CustomerConsent> findByIdAndCustomerId(UUID id, UUID customerId);
    boolean existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(UUID customerId,
                                                                                                  com.nicko.customer.entity.enums.ConsentType type, String version);
}
