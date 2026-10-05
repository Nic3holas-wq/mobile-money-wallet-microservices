package com.nicko.customer.mapper;

import com.nicko.customer.entity.CustomerContact;
import com.nicko.customer.dto.CustomerContactRequest;
import com.nicko.customer.dto.CustomerContactResponse;
import org.springframework.stereotype.Component;

@Component
@lombok.RequiredArgsConstructor
public class CustomerContactMapper {
    private final com.nicko.customer.service.ContactNormalizer normalizer;

    public void update(CustomerContact entity, CustomerContactRequest request) {
        entity.setContactType(request.contactType());
        entity.setContactValue(normalizedValue(request));
        entity.setPrimary(request.primary());
    }

    public String normalizedValue(CustomerContactRequest request) {
        return normalizer.normalize(request);
    }

    public CustomerContactResponse toResponse(CustomerContact entity) {
        return new CustomerContactResponse(
                entity.getId(),
                entity.getContactType(),
                entity.getContactValue(),
                entity.isPrimary(),
                entity.isVerified(),
                entity.getVerifiedAt(),
                entity.getVerificationSource(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
