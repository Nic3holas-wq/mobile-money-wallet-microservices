package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record KycProfileRequest(@NotNull KycTier requestedTier,
        @Size(max = 255) String occupation,
        @Size(max = 255) String employerName,
        @NotNull SourceOfFunds sourceOfFunds,
        @DecimalMin("0") @Digits(integer = 18, fraction = 4) BigDecimal expectedMonthlyVolume) {}
