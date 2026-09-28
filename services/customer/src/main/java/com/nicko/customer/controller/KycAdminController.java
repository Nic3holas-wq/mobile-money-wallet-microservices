package com.nicko.customer.controller;

import com.nicko.customer.dto.*;
import com.nicko.customer.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}/kyc-profile")
@RequiredArgsConstructor
public class KycAdminController {
    private final KycProfileService profiles;
    private final KycDocumentService documents;
    @GetMapping
    public KycProfileResponse get(@PathVariable UUID customerId) { return profiles.getForReview(customerId); }
    @PostMapping("/review")
    public KycProfileResponse review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId,
            @Valid @RequestBody KycReviewRequest request) { return profiles.review(customerId, userId(jwt), request); }
    @GetMapping("/documents")
    public PageResponse<KycDocumentResponse> documents(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return documents.listForReview(customerId, page, size);
    }
    @PostMapping("/documents/{id}/review")
    public KycDocumentResponse reviewDocument(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId,
            @PathVariable UUID id, @Valid @RequestBody KycDocumentReviewRequest request) {
        return documents.review(customerId, id, userId(jwt), request);
    }
}
