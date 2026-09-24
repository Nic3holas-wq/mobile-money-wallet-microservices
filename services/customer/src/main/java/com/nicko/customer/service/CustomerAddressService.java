package com.nicko.customer.service;

import com.nicko.customer.customer.Customer;
import com.nicko.customer.customer.CustomerAddress;
import com.nicko.customer.dto.CustomerAddressRequest;
import com.nicko.customer.dto.CustomerAddressResponse;
import com.nicko.customer.dto.PageResponse;
import com.nicko.customer.mapper.CustomerAddressMapper;
import com.nicko.customer.repository.CustomerAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerAddressService {
    private final CustomerAddressRepository repository;
    private final CustomerAddressMapper mapper;
    private final CustomerOwnership ownership;

    @Transactional(readOnly = true)
    public PageResponse<CustomerAddressResponse> list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size between 1 and 100");
        }
        Customer customer = ownership.require(userId);
        return PageResponse.from(repository.findByCustomerId(customer.getId(),
                PageRequest.of(page, size, Sort.by("createdAt", "id"))).map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    public CustomerAddressResponse get(UUID userId, UUID id) {
        return mapper.toResponse(find(id, ownership.require(userId).getId()));
    }

    @Transactional
    public CustomerAddressResponse create(UUID userId, CustomerAddressRequest request) {
        return save(userId, null, request);
    }

    @Transactional
    public CustomerAddressResponse update(UUID userId, UUID id, CustomerAddressRequest request) {
        return save(userId, id, request);
    }

    private CustomerAddressResponse save(UUID userId, UUID id, CustomerAddressRequest request) {
        Customer customer = ownership.lock(userId);
        CustomerAddress entity = id == null ? new CustomerAddress() : find(id, customer.getId());
        if (request.primary()) {
            repository.findByCustomerIdAndPrimaryTrue(customer.getId()).stream()
                    .filter(existing -> !existing.getId().equals(id)).forEach(existing -> existing.setPrimary(false));
            repository.flush();
        }
        entity.setCustomer(customer);
        mapper.update(entity, request);
        return mapper.toResponse(repository.saveAndFlush(entity));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Customer customer = ownership.lock(userId);
        repository.delete(find(id, customer.getId()));
        repository.flush();
    }

    private CustomerAddress find(UUID id, UUID customerId) {
        return repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
    }
}
