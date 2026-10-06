package com.nicko.wallet.config;

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
public class WalletJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String paymentRole;
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    public WalletJwtAuthenticationConverter(@Value("${app.security.payment-role:payment-service}") String paymentRole) {
        this.paymentRole = paymentRole;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        Object realm = jwt.getClaims().get("realm_access");
        if (realm instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> roles
                && roles.contains(paymentRole)) {
            authorities.add(new SimpleGrantedAuthority("PAYMENT_SERVICE"));
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
