package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record KycDocumentRequest(@NotNull DocumentType documentType,
        @NotBlank @Size(max = 128) String documentNumber,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String issuingCountry,
        @PastOrPresent LocalDate issuedAt,
        @Future LocalDate expiresAt,
        @NotBlank @Size(max = 255) String frontFileReference,
        @Size(max = 255) String backFileReference) {}
