package com.nicko.customer.service;

import java.util.Objects;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.CustomerContact;
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
    private final com.nicko.customer.repository.ContactVerificationRepository challenges;
    private final CustomerAuditService audit;
    private final OutboxEventService outbox;
    private final java.time.Clock clock;

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
        var previousType = entity.getContactType();
        boolean previouslyPrimary = entity.isPrimary();
        String value = mapper.normalizedValue(request);
        boolean duplicate = id == null
                ? repository.existsByContactTypeAndContactValue(request.contactType(), value)
                : repository.existsByContactTypeAndContactValueAndIdNot(request.contactType(), value, id);
        if (duplicate) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Contact is already in use"); }
        boolean changedValue = entity.getContactType() != request.contactType() || !Objects.equals(entity.getContactValue(), value);
        if (entity.isVerified() && changedValue) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Create and verify a replacement contact instead of editing a verified contact");
        }
        if (entity.isVerified() && entity.isPrimary() && !request.primary()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Select a verified replacement primary contact first");
        }
        var existingPrimaries = repository.findByCustomerIdAndContactTypeAndPrimaryTrue(customer.getId(), request.contactType());
        if (request.primary() && !entity.isVerified() && existingPrimaries.stream()
                .anyMatch(c -> !c.getId().equals(id) && c.isVerified())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Verify the replacement before making it primary");
        }
        boolean primaryChanged = entity.isPrimary() != request.primary() || (entity.isPrimary() && changedValue);
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
        if (changedValue && id != null) {
            challenges.findByContactIdAndConsumedAtIsNull(id).forEach(c -> c.setConsumedAt(clock.instant()));
        }
        entity.setCustomer(customer);
        mapper.update(entity, request);
        repository.saveAndFlush(entity);
        audit.record(customer.getId(), userId, id == null ? "CONTACT_CREATED" : "CONTACT_UPDATED", "CUSTOMER_CONTACT", entity.getId(),
                java.util.Map.of(), java.util.Map.of("contactType", entity.getContactType().name(), "primary", entity.isPrimary()));
        if (previouslyPrimary && previousType != request.contactType()) {
            outbox.record(customer, "customer.contact.primary.changed.v1", "CUSTOMER_CONTACT", entity.getId(),
                    java.util.Map.of("contactId", entity.getId().toString(), "contactType", previousType.name(), "primary", false));
        }
        if (primaryChanged) { primaryEvent(customer, entity); }
        return mapper.toResponse(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Customer customer = ownership.lock(userId);
        var entity = find(id, customer.getId());
        if (entity.isVerified() && (entity.isPrimary() || repository.findByCustomerIdAndContactTypeAndPrimaryTrue(
                customer.getId(), entity.getContactType()).stream().noneMatch(c -> c.isVerified() && !c.getId().equals(id)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Select a verified replacement primary contact before removal");
        }
        challenges.findByContactIdAndConsumedAtIsNull(id).forEach(c -> c.setConsumedAt(clock.instant()));
        audit.record(customer.getId(), userId, "CONTACT_DELETED", "CUSTOMER_CONTACT", id,
                java.util.Map.of("contactType", entity.getContactType().name(), "primary", entity.isPrimary()), java.util.Map.of());
        if (entity.isPrimary()) { entity.setPrimary(false); primaryEvent(customer, entity); }
        repository.delete(entity);
        repository.flush();
    }

    private void primaryEvent(Customer customer, CustomerContact contact) {
        outbox.record(customer, "customer.contact.primary.changed.v1", "CUSTOMER_CONTACT", contact.getId(),
                java.util.Map.of("contactId", contact.getId().toString(), "contactType", contact.getContactType().name(), "primary", contact.isPrimary()));
    }

    private CustomerContact find(UUID id, UUID customerId) {
        return repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
    }
}
