package com.nicko.customer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errors,
            CustomerJwtAuthenticationConverter converter) throws Exception {
        return http
                // This API uses bearer tokens, not browser session cookies.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasAuthority("CUSTOMER_ADMIN")
                        .requestMatchers("/api/v1/customers", "/api/v1/customers/me",
                                "/api/v1/customers/me/addresses", "/api/v1/customers/me/addresses/*",
                                "/api/v1/customers/me/contacts", "/api/v1/customers/me/contacts/**",
                                "/api/v1/customers/me/completion",
                                "/api/v1/customers/me/consents", "/api/v1/customers/me/consents/*",
                                "/api/v1/customers/me/consents/*/withdrawal",
                                "/api/v1/customers/me/limits", "/api/v1/customers/me/limits/*",
                                "/api/v1/customers/me/kyc-profile", "/api/v1/customers/me/kyc-profile/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }
}
