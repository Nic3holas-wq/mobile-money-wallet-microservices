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
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/customers/me/kyc-profile")
@RequiredArgsConstructor
public class KycProfileController {
    private final KycProfileService service;
    @PostMapping
    public ResponseEntity<KycProfileResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody KycProfileRequest request) {
        return ResponseEntity.created(URI.create("/api/v1/customers/me/kyc-profile"))
                .body(service.create(userId(jwt), request));
    }
    @GetMapping
    public KycProfileResponse get(@AuthenticationPrincipal Jwt jwt) { return service.get(userId(jwt)); }
    @PutMapping
    public KycProfileResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KycProfileRequest request) {
        return service.update(userId(jwt), request);
    }
    @PostMapping("/submission")
    public KycProfileResponse submit(@AuthenticationPrincipal Jwt jwt) { return service.submit(userId(jwt)); }
}
