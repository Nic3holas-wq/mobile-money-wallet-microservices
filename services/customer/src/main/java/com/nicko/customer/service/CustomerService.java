package com.nicko.customer.service;

import com.nicko.customer.customer.Customer;
import com.nicko.customer.customer.enums.CustomerStatus;
import com.nicko.customer.customer.enums.KycStatus;
import com.nicko.customer.customer.enums.KycTier;
import com.nicko.customer.dto.CustomerResponse;
import com.nicko.customer.dto.RegisterCustomerRequest;
import com.nicko.customer.repository.CustomerRepository;
import com.nicko.customer.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository repository;
    private final CustomerMapper mapper;

    @Transactional
    public CustomerResponse register(UUID userId, RegisterCustomerRequest request) {
        if (repository.existsByKeycloakUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer already registered");
        }
        Customer customer = mapper.toEntity(request);
        customer.setKeycloakUserId(userId);
        customer.setCustomerNumber("CUS-" + UUID.randomUUID());
        customer.setCustomerStatus(CustomerStatus.PENDING);
        customer.setKycStatus(KycStatus.NOT_STARTED);
        customer.setKycTier(KycTier.TIER_0);
        customer.setWalletEligible(false);
        // Flush here so database uniqueness violations reach the API error handler.
        return mapper.toResponse(repository.saveAndFlush(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCurrent(UUID userId) {
        return repository.findByKeycloakUserId(userId).map(mapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not registered"));
    }
}
