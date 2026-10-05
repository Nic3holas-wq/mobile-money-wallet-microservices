package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record KycDocumentResponse(UUID id, DocumentType documentType, String issuingCountry, LocalDate issuedAt, LocalDate expiresAt, String frontFileReference, String backFileReference, DocumentVerificationStatus verificationStatus, String failureReason, Instant verifiedAt, Instant createdAt, Instant updatedAt) {}
