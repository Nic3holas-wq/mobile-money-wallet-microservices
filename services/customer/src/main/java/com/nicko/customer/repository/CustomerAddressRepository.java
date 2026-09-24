package com.nicko.customer.repository;

import java.util.List;
import com.nicko.customer.customer.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, UUID> {
    Page<CustomerAddress> findByCustomerId(UUID customerId, Pageable pageable);
    Optional<CustomerAddress> findByIdAndCustomerId(UUID id, UUID customerId);
    List<CustomerAddress> findByCustomerIdAndPrimaryTrue(UUID customerId);
}
