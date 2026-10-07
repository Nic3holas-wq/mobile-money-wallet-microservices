package com.nicko.customer.entity;

import com.nicko.customer.mapper.CustomerMapper;
import com.nicko.customer.repository.CustomerRepository;
import com.nicko.customer.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerApiTests {
    @Test void serviceReturnsOnlyTheCustomerMatchingTheAuthenticatedSubject() {
        CustomerRepository repository = mock(CustomerRepository.class);
        CustomerMapper mapper = new CustomerMapper();
        CustomerService service = service(repository, mapper);
        UUID user = UUID.randomUUID();
        Customer customer = customer(user);
        when(repository.findByKeycloakUserId(user)).thenReturn(Optional.of(customer));
        assertEquals(customer.getId(), service.getCurrent(user).id());
        when(repository.findByKeycloakUserId(user)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.getCurrent(user)).getStatusCode());
    }

    @Test void registrationRejectsDuplicateAndUnderageCustomersBeforePersistence() {
        CustomerRepository repository = mock(CustomerRepository.class);
        UUID user = UUID.randomUUID();
        when(repository.existsByKeycloakUserId(user)).thenReturn(true);
        CustomerService service = service(repository, mock(CustomerMapper.class));
        var request = new com.nicko.customer.dto.RegisterCustomerRequest("A", null, "B", LocalDate.of(1990, 1, 1), null, "KE", "en");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.register(user, request)).getStatusCode());
        assertThrows(FieldValidationException.class, () -> service.register(user,
                new com.nicko.customer.dto.RegisterCustomerRequest("A", null, "B", LocalDate.of(2015, 1, 1), null, "KE", "en")));
        verify(repository, never()).saveAndFlush(any());
    }

    private static CustomerService service(CustomerRepository repository, CustomerMapper mapper) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        return new CustomerService(repository, mapper, mock(CustomerOwnership.class), mock(CustomerAuditService.class),
                mock(OutboxEventService.class), clock, mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new OnboardingPolicy(clock, 18), mock(CustomerCompletionService.class));
    }

    private static Customer customer(UUID user) {
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID()); customer.setKeycloakUserId(user);
        customer.setFirstName("A"); customer.setLastName("B"); customer.setCustomerNumber("CUS-1");
        customer.setDateOfBirth(LocalDate.of(1990, 1, 1)); customer.setNationality("KE"); customer.setPreferredLanguage("en");
        customer.setCustomerStatus(com.nicko.customer.entity.enums.CustomerStatus.PENDING);
        customer.setKycStatus(com.nicko.customer.entity.enums.KycStatus.NOT_STARTED);
        customer.setKycTier(com.nicko.customer.entity.enums.KycTier.TIER_0);
        return customer;
    }
}
