package com.nicko.customer.dto;
import java.time.Instant;
import java.util.UUID;
public record VerificationChallengeResponse(UUID id, Instant expiresAt, Instant resendAfter, String status) {}
