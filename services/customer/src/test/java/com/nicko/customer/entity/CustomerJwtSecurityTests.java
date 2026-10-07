package com.nicko.customer.entity;

import com.nicko.customer.config.AuthenticatedCustomer;
import com.nicko.customer.config.CustomerJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CustomerJwtSecurityTests {
    @Test void acceptsOnlyUuidSubjectsAndExtractsConfiguredAdminRole() {
        UUID user = UUID.randomUUID();
        Jwt customer = Jwt.withTokenValue("unit-token").header("alg", "none").subject(user.toString())
                .claim("scope", "openid profile").claim("realm_access", Map.of("roles", List.of("admin"))).build();
        assertEquals(user, AuthenticatedCustomer.userId(customer));
        var customerAuthorities = new CustomerJwtAuthenticationConverter("customer-admin").convert(customer).getAuthorities();
        assertFalse(customerAuthorities.stream().anyMatch(authority -> authority.getAuthority().equals("CUSTOMER_ADMIN")));

        Jwt staff = Jwt.withTokenValue("unit-token").header("alg", "none").subject(user.toString())
                .claim("realm_access", Map.of("roles", List.of("customer-admin"))).build();
        assertTrue(new CustomerJwtAuthenticationConverter("customer-admin").convert(staff).getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).anyMatch("CUSTOMER_ADMIN"::equals));
        Jwt invalid = Jwt.withTokenValue("unit-token").header("alg", "none").subject("not-a-uuid").build();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> AuthenticatedCustomer.userId(invalid)).getStatusCode());
    }
}
