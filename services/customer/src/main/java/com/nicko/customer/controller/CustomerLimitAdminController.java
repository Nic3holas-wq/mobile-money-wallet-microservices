package com.nicko.customer.controller;

import com.nicko.customer.dto.*;
import com.nicko.customer.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}/limits")
@RequiredArgsConstructor
public class CustomerLimitAdminController {
    private final CustomerLimitService service;
    @GetMapping
    public PageResponse<CustomerLimitResponse> list(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(customerId, page, size);
    }
    @PostMapping
    public ResponseEntity<CustomerLimitResponse> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID customerId, @Valid @RequestBody CustomerLimitRequest request) {
        var response = service.create(customerId, userId(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/admin/customers/" + customerId + "/limits/" + response.id())).body(response);
    }
    @GetMapping("/{id}")
    public CustomerLimitResponse get(@PathVariable UUID customerId, @PathVariable UUID id) {
        return service.getForAdmin(customerId, id);
    }
    @PutMapping("/{id}")
    public CustomerLimitResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId, @PathVariable UUID id,
            @Valid @RequestBody CustomerLimitRequest request) {
        return service.update(customerId, id, userId(jwt), request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId, @PathVariable UUID id,
            @Valid @RequestBody LimitRemovalRequest request) {
        service.delete(customerId, id, userId(jwt), request.reason());
        return ResponseEntity.noContent().build();
    }
}
