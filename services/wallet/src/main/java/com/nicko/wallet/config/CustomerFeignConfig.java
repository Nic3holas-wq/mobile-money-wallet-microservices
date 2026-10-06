package com.nicko.wallet.config;

import com.nicko.wallet.exception.CustomerNotFoundException;
import com.nicko.wallet.exception.CustomerServiceException;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.apache.hc.core5.http.HttpHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.file.AccessDeniedException;

public class CustomerFeignConfig {

    // Forward the caller's JWT to the customer service
    @Bean
    RequestInterceptor bearerTokenInterceptor() {
        return template -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                template.header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwtAuth.getToken().getTokenValue());
            }
        };
    }

    // Turn HTTP errors into meaningful exceptions
    @Bean
    ErrorDecoder customerErrorDecoder() {
        return (methodKey, response) -> switch (response.status()) {
            case 404 -> new CustomerNotFoundException("Customer not found");
            case 401, 403 -> new AccessDeniedException("Not allowed to call customer service");
            default -> new CustomerServiceException(
                    "Customer service error: " + response.status());
        };
    }
}
