package com.nicko.customer.service;

import com.nicko.customer.dto.CustomerLimitRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.mapper.CustomerLimitMapper;
import com.nicko.customer.repository.CustomerLimitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerLimitServiceUnitTests {
    @Test void createPersistsLimitAndRecordsAuditAndOutbox() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerLimitRepository repository = mock(CustomerLimitRepository.class);
        CustomerLimitMapper mapper = mock(CustomerLimitMapper.class);
        CustomerAuditService audit = mock(CustomerAuditService.class);
        OutboxEventService outbox = mock(OutboxEventService.class);
        Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.lockById(customer.getId())).thenReturn(customer);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            com.nicko.customer.entity.CustomerLimit entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
        CustomerLimitService service = new CustomerLimitService(ownership, repository, mapper, audit, outbox);
        UUID actor = UUID.randomUUID();
        service.create(customer.getId(), actor, request("100", "500", "1000", null));
        verify(repository).saveAndFlush(any());
        verify(audit).record(eq(customer.getId()), eq(actor), eq("LIMIT_CREATED"), eq("CUSTOMER_LIMIT"), any(), anyMap(), anyMap());
        verify(outbox).record(eq(customer), eq("customer.limit.changed.v1"), eq("CUSTOMER_LIMIT"), any(), anyMap());
    }

    @Test void rejectsInconsistentThresholdsAndInvalidRemovalReason() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerLimitRepository repository = mock(CustomerLimitRepository.class);
        CustomerLimitService service = new CustomerLimitService(ownership, repository, mock(CustomerLimitMapper.class),
                mock(CustomerAuditService.class), mock(OutboxEventService.class));
        UUID customerId = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(customerId);
        when(ownership.lockById(customerId)).thenReturn(customer);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(customerId, UUID.randomUUID(), request("600", "500", "1000", null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.delete(customerId, UUID.randomUUID(), UUID.randomUUID(), " ")).getStatusCode());
        verify(repository, never()).saveAndFlush(any());
    }

    private static CustomerLimitRequest request(String per, String daily, String monthly, Instant until) {
        return new CustomerLimitRequest(com.nicko.customer.entity.enums.TransactionType.TRANSFER, "KES",
                new BigDecimal(per), new BigDecimal(daily), new BigDecimal(monthly), 10,
                Instant.parse("2026-01-01T00:00:00Z"), until, "unit test");
    }
}
