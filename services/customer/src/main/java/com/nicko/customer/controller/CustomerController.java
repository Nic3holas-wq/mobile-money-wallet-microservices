package com.nicko.customer.controller;

import com.nicko.customer.dto.CustomerResponse;
import com.nicko.customer.service.CustomerService;
import com.nicko.customer.dto.RegisterCustomerRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService service;

    @PostMapping
    public ResponseEntity<CustomerResponse> register(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody RegisterCustomerRequest request) {
        return ResponseEntity.created(URI.create("/api/v1/customers/me"))
                .body(service.register(userId(jwt), request));
    }

    @GetMapping("/me")
    public CustomerResponse getCurrent(@AuthenticationPrincipal Jwt jwt) {
        return service.getCurrent(userId(jwt));
    }

}
