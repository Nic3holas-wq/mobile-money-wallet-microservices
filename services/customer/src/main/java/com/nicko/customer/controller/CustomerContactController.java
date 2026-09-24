package com.nicko.customer.controller;

import com.nicko.customer.dto.CustomerContactRequest;
import com.nicko.customer.dto.CustomerContactResponse;
import com.nicko.customer.dto.PageResponse;
import com.nicko.customer.service.CustomerContactService;
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
@RequestMapping("/api/v1/customers/me/contacts")
@RequiredArgsConstructor
public class CustomerContactController {
    private final CustomerContactService service;

    @PostMapping
    public ResponseEntity<CustomerContactResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CustomerContactRequest request) {
        CustomerContactResponse response = service.create(userId(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/customers/me/contacts/" + response.id())).body(response);
    }

    @GetMapping
    public PageResponse<CustomerContactResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(userId(jwt), page, size);
    }

    @GetMapping("/{id}")
    public CustomerContactResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    @PutMapping("/{id}")
    public CustomerContactResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody CustomerContactRequest request) {
        return service.update(userId(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
