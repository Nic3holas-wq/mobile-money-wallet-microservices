package com.nicko.customer.repository;

import com.nicko.customer.entity.CustomerAuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import java.util.UUID;

// Deliberately exposes no update/delete operations.
public interface CustomerAuditRepository extends Repository<CustomerAuditRecord, UUID> {
    Page<CustomerAuditRecord> findByCustomerId(UUID customerId, Pageable pageable);
}
