package com.nicko.customer.repository;

import com.nicko.customer.customer.CustomerLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface CustomerLimitRepository extends JpaRepository<CustomerLimit, UUID> {
    Page<CustomerLimit> findByCustomerId(UUID customerId, Pageable pageable);
    Optional<CustomerLimit> findByIdAndCustomerId(UUID id, UUID customerId);
}
