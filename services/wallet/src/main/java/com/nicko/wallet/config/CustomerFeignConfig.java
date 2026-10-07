package com.nicko.wallet.config;

import com.nicko.wallet.exception.CustomerNotFoundException;
import com.nicko.wallet.exception.CustomerServiceException;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

import java.nio.file.AccessDeniedException;

public class CustomerFeignConfig {

    // Turn HTTP errors into meaningful exceptions
    @Bean
    ErrorDecoder customerErrorDecoder() {
        return (methodKey, response) -> switch (response.status()) {
            case 404 -> new CustomerNotFoundException("Customer not found");
            case 401, 403 -> new AccessDeniedException(
                    "Customer service denied the forwarded request (HTTP " + response.status() + ")");
            default -> new CustomerServiceException(
                    "Customer service error: " + response.status());
        };
    }
}
