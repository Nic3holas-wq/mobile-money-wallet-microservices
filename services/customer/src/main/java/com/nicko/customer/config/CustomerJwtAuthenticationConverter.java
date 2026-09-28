package com.nicko.customer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Component
public class CustomerJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final String adminRole;
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    public CustomerJwtAuthenticationConverter(@Value("${app.security.admin-role:customer-admin}") String adminRole) {
        this.adminRole = adminRole;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        Object realm = jwt.getClaims().get("realm_access");
        if (realm instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> roles
                && roles.contains(adminRole)) {
            authorities.add(new SimpleGrantedAuthority("CUSTOMER_ADMIN"));
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
