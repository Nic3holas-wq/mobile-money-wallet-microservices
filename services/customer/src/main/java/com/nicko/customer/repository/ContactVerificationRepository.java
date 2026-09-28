package com.nicko.customer.repository;

import com.nicko.customer.customer.ContactVerificationChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactVerificationRepository extends JpaRepository<ContactVerificationChallenge, UUID> {
    Optional<ContactVerificationChallenge> findByIdAndCustomerIdAndContactId(UUID id, UUID customerId, UUID contactId);
    Optional<ContactVerificationChallenge> findFirstByContactIdOrderByCreatedAtDesc(UUID contactId);
    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant cutoff);
    List<ContactVerificationChallenge> findByContactIdAndConsumedAtIsNull(UUID contactId);
}
