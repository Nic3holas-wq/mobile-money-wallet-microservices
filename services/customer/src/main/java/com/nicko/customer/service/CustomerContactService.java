package com.nicko.customer.service;

import java.util.Objects;
import com.nicko.customer.customer.Customer;
import com.nicko.customer.customer.CustomerContact;
import com.nicko.customer.dto.CustomerContactRequest;
import com.nicko.customer.dto.CustomerContactResponse;
import com.nicko.customer.dto.PageResponse;
import com.nicko.customer.mapper.CustomerContactMapper;
import com.nicko.customer.repository.CustomerContactRepository;
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
public class CustomerContactService {
    private final CustomerContactRepository repository;
    private final CustomerContactMapper mapper;
    private final CustomerOwnership ownership;

    @Transactional(readOnly = true)
    public PageResponse<CustomerContactResponse> list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size between 1 and 100");
        }
        Customer customer = ownership.require(userId);
        return PageResponse.from(repository.findByCustomerId(customer.getId(),
                PageRequest.of(page, size, Sort.by("createdAt", "id"))).map(mapper::toResponse));
    }

    @Transactional(readOnly = true)
    public CustomerContactResponse get(UUID userId, UUID id) {
        return mapper.toResponse(find(id, ownership.require(userId).getId()));
    }

    @Transactional
    public CustomerContactResponse create(UUID userId, CustomerContactRequest request) {
        return save(userId, null, request);
    }

    @Transactional
    public CustomerContactResponse update(UUID userId, UUID id, CustomerContactRequest request) {
        return save(userId, id, request);
    }

    private CustomerContactResponse save(UUID userId, UUID id, CustomerContactRequest request) {
        Customer customer = ownership.lock(userId);
        CustomerContact entity = id == null ? new CustomerContact() : find(id, customer.getId());
        String value = mapper.normalizedValue(request);
        boolean duplicate = id == null
                ? repository.existsByContactTypeAndContactValue(request.contactType(), value)
                : repository.existsByContactTypeAndContactValueAndIdNot(request.contactType(), value, id);
        if (duplicate) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Contact is already in use"); }
        if (request.primary()) {
            repository.findByCustomerIdAndContactTypeAndPrimaryTrue(customer.getId(), request.contactType()).stream()
                    .filter(existing -> !existing.getId().equals(id)).forEach(existing -> existing.setPrimary(false));
            repository.flush();
        }
        if (entity.getContactType() != request.contactType()
                || !Objects.equals(entity.getContactValue(), mapper.normalizedValue(request))) {
            entity.setVerified(false);
            entity.setVerifiedAt(null);
            entity.setVerificationSource(null);
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

    private CustomerContact find(UUID id, UUID customerId) {
        return repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
    }
}
