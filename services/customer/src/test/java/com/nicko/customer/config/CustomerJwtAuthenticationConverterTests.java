package com.nicko.customer.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CustomerJwtAuthenticationConverterTests {
    private final CustomerJwtAuthenticationConverter converter = new CustomerJwtAuthenticationConverter("customer-admin");
    @Test
    void grantsStaffAuthorityOnlyFromTheConfiguredRealmRole() {
        Jwt staff = Jwt.withTokenValue("test").header("alg", "RS256").subject("user")
                .claim("realm_access", Map.of("roles", List.of("customer-admin"))).claim("scope", "openid").build();
        assertThat(converter.convert(staff).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("CUSTOMER_ADMIN", "SCOPE_openid");
        Jwt customer = Jwt.withTokenValue("test").header("alg", "RS256").subject("user")
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .claim("scope", "CUSTOMER_ADMIN").build();
        assertThat(converter.convert(customer).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .doesNotContain("CUSTOMER_ADMIN");
    }
}
