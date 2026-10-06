package com.nicko.customer.repository;

import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.enums.CustomerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    List<Customer> findAllByCustomerStatusAndWalletEligibleTrue(CustomerStatus customerStatus);
    Optional<Customer> findByKeycloakUserId(UUID keycloakUserId);
    Optional<Customer> findByCustomerNumber(String customerNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.keycloakUserId = :userId")
    Optional<Customer> findForUpdateByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findForUpdateById(@Param("id") UUID id);

    boolean existsByKeycloakUserId(UUID keycloakUserId);
}
