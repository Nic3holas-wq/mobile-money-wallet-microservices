package com.nicko.customer.controller;

import com.nicko.customer.dto.*;
import com.nicko.customer.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/customers/me/limits")
@RequiredArgsConstructor
public class CustomerLimitController {
    private final CustomerLimitService service;
    @GetMapping
    public PageResponse<CustomerLimitResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listMine(userId(jwt), page, size);
    }
    @GetMapping("/{id}")
    public CustomerLimitResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getMine(userId(jwt), id);
    }
}
