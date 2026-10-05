package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public record CustomerLimitResponse(UUID id, TransactionType transactionType, String currency, BigDecimal perTransactionLimit, BigDecimal dailyLimit, BigDecimal monthlyLimit, Integer dailyCountLimit, Instant effectiveFrom, Instant effectiveUntil, String reason, Instant createdAt, Instant updatedAt) {}
