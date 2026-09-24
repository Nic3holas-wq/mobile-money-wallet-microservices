package com.nicko.customer.mapper;

import com.nicko.customer.customer.CustomerAddress;
import com.nicko.customer.dto.CustomerAddressRequest;
import com.nicko.customer.dto.CustomerAddressResponse;
import org.springframework.stereotype.Component;

@Component
public class CustomerAddressMapper {
    public void update(CustomerAddress entity, CustomerAddressRequest request) {
        entity.setAddressType(request.addressType());
        entity.setCountryCode(request.countryCode());
        entity.setCounty(request.county().strip());
        entity.setCityOrTown(request.cityOrTown().strip());
        entity.setPostalCode(request.postalCode() == null ? null : request.postalCode().strip());
        entity.setAddressLine1(request.addressLine1().strip());
        entity.setAddressLine2(request.addressLine2() == null ? null : request.addressLine2().strip());
        entity.setPrimary(request.primary());
    }

    public CustomerAddressResponse toResponse(CustomerAddress entity) {
        return new CustomerAddressResponse(entity.getId(), entity.getAddressType(), entity.getCountryCode(), entity.getCounty(), entity.getCityOrTown(), entity.getPostalCode(), entity.getAddressLine1(), entity.getAddressLine2(),
                entity.isPrimary(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
