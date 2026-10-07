package com.nicko.customer.service;

import com.nicko.customer.dto.CustomerAddressRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.CustomerAddress;
import com.nicko.customer.entity.enums.AddressType;
import com.nicko.customer.mapper.CustomerAddressMapper;
import com.nicko.customer.repository.CustomerAddressRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerAddressServiceUnitTests {
    @Test void creatingPrimaryAddressDemotesExistingPrimaryBeforeSaving() {
        CustomerAddressRepository repository = mock(CustomerAddressRepository.class);
        CustomerAddressMapper mapper = mock(CustomerAddressMapper.class);
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerAddressService service = new CustomerAddressService(repository, mapper, ownership);
        Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.lock(any())).thenReturn(customer);
        CustomerAddress previous = new CustomerAddress(); previous.setId(UUID.randomUUID()); previous.setPrimary(true);
        when(repository.findByCustomerIdAndPrimaryTrue(customer.getId())).thenReturn(List.of(previous));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new CustomerAddressRequest(AddressType.HOME, "KE", "Nairobi", "Nairobi", null, "Road 1", null, true);
        service.create(UUID.randomUUID(), request);
        assertFalse(previous.isPrimary());
        verify(repository).flush();
        verify(repository).saveAndFlush(any(CustomerAddress.class));
    }

    @Test void rejectsInvalidPageAndUnknownAddress() {
        CustomerAddressRepository repository = mock(CustomerAddressRepository.class);
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerAddressService service = new CustomerAddressService(repository, mock(CustomerAddressMapper.class), ownership);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.list(UUID.randomUUID(), 0, 101)).getStatusCode());
        Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.require(any())).thenReturn(customer);
        when(repository.findByIdAndCustomerId(any(), eq(customer.getId()))).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.get(UUID.randomUUID(), UUID.randomUUID())).getStatusCode());
    }
}
