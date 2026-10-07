package com.nicko.customer.entity;

import com.nicko.customer.mapper.CustomerAddressMapper;
import com.nicko.customer.mapper.CustomerContactMapper;
import com.nicko.customer.repository.CustomerAddressRepository;
import com.nicko.customer.repository.CustomerContactRepository;
import com.nicko.customer.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerResourcesApiTests {
    @Test void addressAndContactLookupsAreAlwaysScopedToTheResolvedCustomer() {
        UUID user = UUID.randomUUID(), customerId = UUID.randomUUID(), resourceId = UUID.randomUUID();
        Customer customer = new Customer(); customer.setId(customerId);
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        when(ownership.require(user)).thenReturn(customer);

        CustomerAddressRepository addresses = mock(CustomerAddressRepository.class);
        CustomerAddressService addressService = new CustomerAddressService(addresses, new CustomerAddressMapper(), ownership);
        when(addresses.findByIdAndCustomerId(resourceId, customerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> addressService.get(user, resourceId)).getStatusCode());
        verify(addresses).findByIdAndCustomerId(resourceId, customerId);

        CustomerContactRepository contacts = mock(CustomerContactRepository.class);
        CustomerContactService contactService = new CustomerContactService(contacts,
                new CustomerContactMapper(new ContactNormalizer()), ownership,
                mock(com.nicko.customer.repository.ContactVerificationRepository.class), mock(CustomerAuditService.class),
                mock(OutboxEventService.class), java.time.Clock.systemUTC());
        when(contacts.findByIdAndCustomerId(resourceId, customerId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> contactService.get(user, resourceId)).getStatusCode());
        verify(contacts).findByIdAndCustomerId(resourceId, customerId);
    }
}
