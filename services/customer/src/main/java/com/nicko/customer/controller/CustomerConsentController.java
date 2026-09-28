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
@RequestMapping("/api/v1/customers/me/consents")
@RequiredArgsConstructor
public class CustomerConsentController {
    private final CustomerConsentService service;
    private final ConsentPolicyService policies;

    @PostMapping
    public ResponseEntity<CustomerConsentResponse> accept(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CustomerConsentRequest request, jakarta.servlet.http.HttpServletRequest http) {
        java.net.InetAddress ip = null;
        // Remote address comes from the container; do not trust arbitrary forwarded headers here.
        String remote = http.getRemoteAddr();
        if (remote != null && remote.matches("[0-9a-fA-F:.]+")) {
            try { ip = java.net.InetAddress.getByName(remote); } catch (java.net.UnknownHostException ignored) { }
        }
        var response = service.accept(userId(jwt), request, ip, http.getHeader("User-Agent"));
        return ResponseEntity.created(URI.create("/api/v1/customers/me/consents/" + response.id())).body(response);
    }
    @GetMapping
    public PageResponse<CustomerConsentResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(userId(jwt), page, size);
    }
    @GetMapping("/policies")
    public java.util.List<ConsentPolicyResponse> policies() { return policies.policies(); }
    @GetMapping("/current")
    public java.util.List<CurrentConsentResponse> current(@AuthenticationPrincipal Jwt jwt) {
        return service.current(userId(jwt));
    }
    @GetMapping("/{id}")
    public CustomerConsentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }
    @PostMapping("/{id}/withdrawal")
    public CustomerConsentResponse withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.withdraw(userId(jwt), id);
    }
}
