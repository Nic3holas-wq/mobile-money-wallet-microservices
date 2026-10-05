package com.nicko.customer.service;

import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.OutboxEvent;
import com.nicko.customer.entity.enums.OutboxStatus;
import com.nicko.customer.dto.*;
import com.nicko.customer.mapper.OutboxEventMapper;
import com.nicko.customer.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {
    private final OutboxEventRepository repository;
    private final OutboxEventMapper mapper;
    private final CustomerOwnership ownership;

    // Internal only: no HTTP endpoint accepts arbitrary event payloads or delivery state.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Customer customer, String eventType, UUID aggregateId) {
        record(customer, eventType, "KYC_PROFILE", aggregateId,
                Map.of("customerId", customer.getId().toString(), "kycProfileId", aggregateId.toString(),
                        "kycStatus", customer.getKycStatus().name()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Customer customer, String eventType, String aggregateType, UUID aggregateId,
            Map<String, Object> data) {
        var event = new OutboxEvent();
        // Persist first to obtain the generated stable event ID; payload is completed before flush.
        event.setCustomer(customer);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setCorrelationId(BusinessCorrelation.current());
        event.setStatus(OutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setPayload(Map.of());
        repository.save(event);
        event.setPayload(Map.of("eventId", event.getId().toString(), "eventType", eventType,
                "schemaVersion", 1, "occurredAt", java.time.Instant.now().toString(),
                "customerId", customer.getId().toString(), "correlationId", event.getCorrelationId().toString(),
                "data", Map.copyOf(data)));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<OutboxEventResponse> list(UUID customerId, int page, int size) {
        ownership.requireById(customerId);
        return PageResponse.from(repository.findByCustomerId(customerId, ApiPages.of(page, size)).map(mapper::toResponse));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional(readOnly = true)
    public OutboxEventResponse get(UUID customerId, UUID id) {
        ownership.requireById(customerId);
        return mapper.toResponse(repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox event not found")));
    }
}
