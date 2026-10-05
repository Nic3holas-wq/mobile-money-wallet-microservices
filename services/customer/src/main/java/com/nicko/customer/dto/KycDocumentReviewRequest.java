package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import jakarta.validation.constraints.*;

public record KycDocumentReviewRequest(@NotNull DocumentVerificationStatus decision,
        @NotBlank @Size(max = 255) String verificationProvider,
        @Size(max = 255) String providerReference,
        @Size(max = 255) String failureReason) {}
