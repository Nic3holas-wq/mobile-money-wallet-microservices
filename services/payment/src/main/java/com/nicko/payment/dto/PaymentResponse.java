package com.nicko.payment.dto;

import com.nicko.payment.entity.enums.PaymentStatus;
import com.nicko.payment.entity.enums.PaymentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        String reference,
        PaymentType type,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String provider,
        String providerReference,
        Instant createdAt,
        Instant completedAt,
        String message
) {
}
