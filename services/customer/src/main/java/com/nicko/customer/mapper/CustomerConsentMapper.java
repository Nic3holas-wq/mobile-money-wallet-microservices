package com.nicko.customer.mapper;

import com.nicko.customer.customer.CustomerConsent;
import com.nicko.customer.dto.CustomerConsentResponse;
import org.springframework.stereotype.Component;

@Component
public class CustomerConsentMapper {
    public CustomerConsentResponse toResponse(CustomerConsent entity) {
        return new CustomerConsentResponse(
                entity.getId(),
                entity.getConsentType(),
                entity.getDocumentVersion(),
                entity.getChannel(),
                entity.isAccepted(),
                entity.getAcceptedAt(),
                entity.getWithdrawnAt(),
                entity.getCreatedAt());
    }
}
