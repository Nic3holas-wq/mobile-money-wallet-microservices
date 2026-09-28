package com.nicko.customer.service;

import com.nicko.customer.customer.CustomerLimit;
import com.nicko.customer.dto.*;
import com.nicko.customer.mapper.CustomerLimitMapper;
import com.nicko.customer.repository.CustomerLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerLimitService {
    private final CustomerOwnership ownership;
    private final CustomerLimitRepository repository;
    private final CustomerLimitMapper mapper;
    private final CustomerAuditService audit;
    private final OutboxEventService outbox;

    @Transactional(readOnly = true)
    public PageResponse<CustomerLimitResponse> listMine(UUID userId, int page, int size) {
        return listFor(ownership.require(userId).getId(), page, size);
    }

    @Transactional(readOnly = true)
    public CustomerLimitResponse getMine(UUID userId, UUID id) {
        return mapper.toResponse(find(ownership.require(userId).getId(), id));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<CustomerLimitResponse> list(UUID customerId, int page, int size) {
        ownership.requireById(customerId);
        return listFor(customerId, page, size);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional
    public CustomerLimitResponse create(UUID customerId, UUID actor, CustomerLimitRequest request) {
        var entity = new CustomerLimit();
        entity.setCustomer(ownership.lockById(customerId));
        entity.setCreatedByKeycloakId(actor);
        apply(entity, request);
        repository.saveAndFlush(entity);
        changed(entity, actor, "LIMIT_CREATED", java.util.Map.of(), AuditSnapshots.limit(entity));
        return mapper.toResponse(entity);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional
    public CustomerLimitResponse update(UUID customerId, UUID id, UUID actor, CustomerLimitRequest request) {
        ownership.lockById(customerId);
        var entity = find(customerId, id);
        var before = AuditSnapshots.limit(entity);
        apply(entity, request);
        repository.saveAndFlush(entity);
        changed(entity, actor, "LIMIT_UPDATED", before, AuditSnapshots.limit(entity));
        return mapper.toResponse(entity);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional
    public void delete(UUID customerId, UUID id, UUID actor, String reason) {
        ownership.lockById(customerId);
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A removal reason of 1 to 255 characters is required");
        }
        var entity = find(customerId, id);
        changed(entity, actor, "LIMIT_DELETED", AuditSnapshots.limit(entity), java.util.Map.of("reason", reason.strip()));
        repository.delete(entity);
        repository.flush();
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public CustomerLimitResponse getForAdmin(UUID customerId, UUID id) {
        ownership.requireById(customerId);
        return mapper.toResponse(find(customerId, id));
    }

    private PageResponse<CustomerLimitResponse> listFor(UUID customerId, int page, int size) {
        return PageResponse.from(repository.findByCustomerId(customerId, ApiPages.of(page, size)).map(mapper::toResponse));
    }

    private CustomerLimit find(UUID customerId, UUID id) {
        return repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Limit not found"));
    }

    private void changed(CustomerLimit entity, UUID actor, String action,
            java.util.Map<String, Object> before, java.util.Map<String, Object> after) {
        audit.record(entity.getCustomer().getId(), actor, action, "CUSTOMER_LIMIT", entity.getId(), before, after);
        outbox.record(entity.getCustomer(), "customer.limit.changed.v1", "CUSTOMER_LIMIT", entity.getId(),
                java.util.Map.of("limitId", entity.getId().toString(), "action", action));
    }

    private void apply(CustomerLimit entity, CustomerLimitRequest request) {
        if (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(request.effectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effectiveUntil must be after effectiveFrom");
        }
        if (request.perTransactionLimit().compareTo(request.dailyLimit()) > 0
                || request.dailyLimit().compareTo(request.monthlyLimit()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limits must satisfy perTransaction <= daily <= monthly");
        }
        mapper.update(entity, request);
    }
}
