package com.nicko.customer.entity;

import com.nicko.customer.dto.CustomerLimitRequest;
import com.nicko.customer.entity.enums.TransactionType;
import com.nicko.customer.mapper.CustomerLimitMapper;
import com.nicko.customer.repository.CustomerLimitRepository;
import com.nicko.customer.repository.KycProfileRepository;
import com.nicko.customer.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemainingEntitiesApiTests {
    @Test void rejectsInvalidCustomerLimitRangesBeforeCallingRepository() {
        CustomerLimitRepository repository = mock(CustomerLimitRepository.class);
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerLimitService service = new CustomerLimitService(ownership, repository, mock(CustomerLimitMapper.class),
                mock(CustomerAuditService.class), mock(OutboxEventService.class));
        UUID customerId = UUID.randomUUID();
        when(ownership.lockById(customerId)).thenReturn(new Customer());
        var request = new CustomerLimitRequest(TransactionType.TRANSFER, "KES", new BigDecimal("200"),
                new BigDecimal("100"), new BigDecimal("500"), 10, Instant.EPOCH, null, "test");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.create(customerId, UUID.randomUUID(), request)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void kycAccessUsesRepositoryAndReturnsNotFoundForMissingProfile() {
        KycProfileRepository repository = mock(KycProfileRepository.class);
        KycAccess access = new KycAccess(repository);
        UUID customerId = UUID.randomUUID();
        when(repository.findByCustomerId(customerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> access.require(customerId)).getStatusCode());
        verify(repository).findByCustomerId(customerId);
    }
}
