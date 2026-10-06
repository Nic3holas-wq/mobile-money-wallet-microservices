package com.nicko.wallet.exception;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String message) {
        super(message);
    }

    public CustomerNotFoundException(UUID customerId) {
        super("Customer not found: " + customerId);
    }
}