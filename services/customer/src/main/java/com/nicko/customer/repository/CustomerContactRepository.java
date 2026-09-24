package com.nicko.customer.repository;

import java.util.List;
import com.nicko.customer.customer.enums.ContactType;
import com.nicko.customer.customer.CustomerContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, UUID> {
    Page<CustomerContact> findByCustomerId(UUID customerId, Pageable pageable);
    Optional<CustomerContact> findByIdAndCustomerId(UUID id, UUID customerId);
    List<CustomerContact> findByCustomerIdAndContactTypeAndPrimaryTrue(UUID customerId,
            ContactType contactType);
    boolean existsByContactTypeAndContactValueAndIdNot(ContactType type,
            String value, UUID id);
    boolean existsByContactTypeAndContactValue(ContactType type, String value);
}
