package com.nicko.customer.mapper;

import com.nicko.customer.customer.Customer;
import com.nicko.customer.dto.CustomerResponse;
import com.nicko.customer.dto.RegisterCustomerRequest;
import org.springframework.stereotype.Component;

@Component
public class CustomerMapper {
    public Customer toEntity(RegisterCustomerRequest request) {
        Customer customer = new Customer();
        customer.setFirstName(request.firstName().strip());
        customer.setMiddleName(request.middleName() == null ? null : request.middleName().strip());
        customer.setLastName(request.lastName().strip());
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setGender(request.gender());
        customer.setNationality(request.nationality());
        customer.setPreferredLanguage(request.preferredLanguage().strip());
        return customer;
    }

    public CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getCustomerNumber(),
                customer.getFirstName(), customer.getMiddleName(), customer.getLastName(),
                customer.getDateOfBirth(), customer.getGender(), customer.getNationality(),
                customer.getPreferredLanguage(), customer.getCustomerStatus(), customer.getKycStatus(),
                customer.getKycTier(), customer.isWalletEligible(), customer.getCreatedAt());
    }
}
