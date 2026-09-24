package com.nicko.customer.repository;

import com.nicko.customer.customer.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByKeycloakUserId(UUID keycloakUserId);
    Optional<Customer> findByCustomerNumber(String customerNumber);
    boolean existsByKeycloakUserId(UUID keycloakUserId);
}
