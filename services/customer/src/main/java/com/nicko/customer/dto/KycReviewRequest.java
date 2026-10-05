package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import jakarta.validation.constraints.*;
import java.time.Instant;

public record KycReviewRequest(@NotNull KycStatus decision,
        KycTier approvedTier,
        @NotNull PepStatus pepStatus,
        @NotNull SanctionsStatus sanctionsStatus,
        @NotNull RiskRating riskRating,
        @Size(max = 255) String rejectionReason,
        @Future Instant expiresAt) {}
