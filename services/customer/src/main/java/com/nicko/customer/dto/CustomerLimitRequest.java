package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.math.BigDecimal;

public record CustomerLimitRequest(@NotNull TransactionType transactionType,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 4) BigDecimal perTransactionLimit,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 4) BigDecimal dailyLimit,
        @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 4) BigDecimal monthlyLimit,
        @Positive Integer dailyCountLimit,
        @NotNull Instant effectiveFrom,
        Instant effectiveUntil,
        @NotBlank @Size(max = 255) String reason) {}
