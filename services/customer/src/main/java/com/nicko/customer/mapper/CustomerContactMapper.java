package com.nicko.customer.mapper;

import com.nicko.customer.customer.enums.ContactType;
import java.util.Locale;
import com.nicko.customer.customer.CustomerContact;
import com.nicko.customer.dto.CustomerContactRequest;
import com.nicko.customer.dto.CustomerContactResponse;
import org.springframework.stereotype.Component;

@Component
public class CustomerContactMapper {
    public void update(CustomerContact entity, CustomerContactRequest request) {
        entity.setContactType(request.contactType());
        entity.setContactValue(normalizedValue(request));
        entity.setPrimary(request.primary());
    }

    public String normalizedValue(CustomerContactRequest request) {
        String value = request.contactValue().strip();
        return request.contactType() == ContactType.EMAIL
                ? value.toLowerCase(Locale.ROOT) : value;
    }

    public CustomerContactResponse toResponse(CustomerContact entity) {
        return new CustomerContactResponse(entity.getId(), entity.getContactType(), entity.getContactValue(),
                entity.isPrimary(), entity.isVerified(), entity.getVerifiedAt(), entity.getVerificationSource(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
