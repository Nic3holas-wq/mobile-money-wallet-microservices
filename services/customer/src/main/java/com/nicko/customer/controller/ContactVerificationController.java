package com.nicko.customer.controller;

import com.nicko.customer.dto.*;
import com.nicko.customer.service.ContactVerificationService;
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
@RequestMapping("/api/v1/customers/me/contacts/{contactId}/verification-challenges")
@RequiredArgsConstructor
public class ContactVerificationController {
    private final ContactVerificationService service;
    @PostMapping
    public ResponseEntity<VerificationChallengeResponse> request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contactId) {
        var response = service.request(userId(jwt), contactId);
        return ResponseEntity.created(URI.create("/api/v1/customers/me/contacts/" + contactId
                + "/verification-challenges/" + response.id())).body(response);
    }
    @GetMapping("/{challengeId}")
    public VerificationChallengeResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contactId,
            @PathVariable UUID challengeId) {
        return service.get(userId(jwt), contactId, challengeId);
    }
    @PostMapping("/{challengeId}/confirmation")
    public CustomerContactResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contactId,
            @PathVariable UUID challengeId, @Valid @RequestBody ConfirmContactRequest request) {
        return service.confirm(userId(jwt), contactId, challengeId, request);
    }
}
