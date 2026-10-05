package com.nicko.customer.service;

import com.nicko.customer.entity.CustomerAuditRecord;
import com.nicko.customer.dto.*;
import com.nicko.customer.repository.CustomerAuditRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerAuditService {
    private final EntityManager entityManager;
    private final com.nicko.customer.mapper.CustomerAuditMapper mapper;
    private final CustomerAuditRepository repository;
    private final CustomerOwnership ownership;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID customerId, UUID actor, String action, String targetType, UUID targetId,
            Map<String, Object> before, Map<String, Object> after) {
        entityManager.persist(new CustomerAuditRecord(customerId, actor, action, targetType, targetId,
                BusinessCorrelation.current(), before, after));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<CustomerAuditResponse> history(UUID customerId, int page, int size) {
        ownership.requireById(customerId);
        ApiPages.of(page, size); // shared bounds validation
        return PageResponse.from(repository.findByCustomerId(customerId,
                PageRequest.of(page, size, Sort.by("occurredAt", "id"))).map(mapper::toResponse));
    }
}
