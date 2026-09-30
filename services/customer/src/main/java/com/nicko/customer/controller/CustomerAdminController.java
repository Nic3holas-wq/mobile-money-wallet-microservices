package com.nicko.customer.controller;

import com.nicko.customer.dto.CustomerResponse;
import com.nicko.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}")
@RequiredArgsConstructor
public class CustomerAdminController {
    private final CustomerService service;

    @PostMapping("/activation")
    public CustomerResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId) {
        return service.activate(customerId, userId(jwt));
    }
}
