package com.nicko.customer.config;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

public final class AuthenticatedCustomer {
    private AuthenticatedCustomer() {}

    public static UUID userId(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        try {
            UUID id = UUID.fromString(subject);
            if (!id.toString().equalsIgnoreCase(subject)) { throw new IllegalArgumentException(); }
            return id;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token subject must be a Keycloak user UUID");
        }
    }
}
