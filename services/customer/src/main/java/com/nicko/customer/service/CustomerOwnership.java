package com.nicko.customer.service;

import com.nicko.customer.customer.Customer;
import com.nicko.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CustomerOwnership {
    private final CustomerRepository customers;

    public Customer require(UUID userId) {
        return customers.findByKeycloakUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not registered"));
    }

    // Called inside the resource service's write transaction. Serializes primary changes per customer.
    public Customer lock(UUID userId) {
        return customers.findForUpdateByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not registered"));
    }
}
