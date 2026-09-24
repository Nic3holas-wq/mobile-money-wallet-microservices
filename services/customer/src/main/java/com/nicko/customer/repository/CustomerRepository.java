package com.nicko.customer.repository;

import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.nicko.customer.customer.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByKeycloakUserId(UUID keycloakUserId);
    Optional<Customer> findByCustomerNumber(String customerNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.keycloakUserId = :userId")
    Optional<Customer> findForUpdateByUserId(@Param("userId") UUID userId);

    boolean existsByKeycloakUserId(UUID keycloakUserId);
}
