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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository repository;
    private final CustomerMapper mapper;
    private final CustomerOwnership ownership;
    private final CustomerAuditService audit;
    private final OutboxEventService outbox;
    private final java.time.Clock clock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final OnboardingPolicy policy;
    private final CustomerCompletionService completion;

    @Transactional
    public CustomerResponse register(UUID userId, RegisterCustomerRequest request) {
        policy.validate(request.dateOfBirth());
        if (repository.existsByKeycloakUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer already registered");
        }
        Customer customer = mapper.toEntity(request);
        customer.setKeycloakUserId(userId);
        long sequence = jdbc.queryForObject("SELECT nextval('customer_public_number_seq')", Long.class);
        customer.setCustomerNumber(String.format(java.util.Locale.ROOT, "CUS-%d-%06d", java.time.LocalDate.now(clock).getYear(), sequence));
        customer.setCustomerStatus(CustomerStatus.PENDING);
        customer.setKycStatus(KycStatus.NOT_STARTED);
        customer.setKycTier(KycTier.TIER_0);
        customer.setWalletEligible(false);
        // Flush here so database uniqueness violations reach the API error handler.
        repository.saveAndFlush(customer);
        audit.record(customer.getId(), userId, "CUSTOMER_REGISTERED", "CUSTOMER", customer.getId(),
                java.util.Map.of(), java.util.Map.of("status", "PENDING"));
        outbox.record(customer, "customer.registered.v1", "CUSTOMER", customer.getId(), java.util.Map.of("status", "PENDING"));
        return mapper.toResponse(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCurrent(UUID userId) {
        return repository.findByKeycloakUserId(userId).map(mapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not registered"));
    }
    @Transactional
    public CustomerResponse update(UUID userId, com.nicko.customer.dto.UpdateCustomerRequest request) {
        var customer = ownership.lock(userId);
        if (request.preferredName() == null && request.preferredLanguage() == null) {
            throw new FieldValidationException("profile", "provide preferredName or preferredLanguage");
        }
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (request.preferredName() != null) {
            String name = request.preferredName().strip();
            String value = name.isEmpty() ? null : name;
            if (!java.util.Objects.equals(value, customer.getPreferredName())) { changed.add("preferredName"); }
            customer.setPreferredName(value);
        }
        if (request.preferredLanguage() != null) {
            String value = request.preferredLanguage().strip();
            if (!value.equals(customer.getPreferredLanguage())) { changed.add("preferredLanguage"); }
            customer.setPreferredLanguage(value);
        }
        if (!changed.isEmpty()) {
            audit.record(customer.getId(), userId, "CUSTOMER_PROFILE_UPDATED", "CUSTOMER", customer.getId(),
                    java.util.Map.of(), java.util.Map.of("changedFields", changed));
        }
        return mapper.toResponse(repository.saveAndFlush(customer));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_ADMIN')")
    @Transactional
    public CustomerResponse activate(UUID customerId, UUID reviewer) {
        var customer = ownership.lockById(customerId);
        if (customer.getKeycloakUserId().equals(reviewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff cannot activate their own customer record");
        }
        if (customer.getCustomerStatus() != CustomerStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending customers can be activated");
        }
        var missing = completion.outstandingSteps(customer);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Customer is not ready for activation: " + String.join(", ", missing));
        }
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        customer.setWalletEligible(true);
        repository.saveAndFlush(customer);
        audit.record(customer.getId(), reviewer, "CUSTOMER_ACTIVATED", "CUSTOMER", customer.getId(),
                java.util.Map.of("status", "PENDING", "walletEligible", false),
                java.util.Map.of("status", "ACTIVE", "walletEligible", true, "kycTier", customer.getKycTier().name()));
        outbox.record(customer, "customer.activated.v1", "CUSTOMER", customer.getId(),
                java.util.Map.of("status", "ACTIVE", "kycTier", customer.getKycTier().name()));
        return mapper.toResponse(customer);
    }
}
