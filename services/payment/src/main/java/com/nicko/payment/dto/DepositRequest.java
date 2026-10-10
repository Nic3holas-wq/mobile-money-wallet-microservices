package com.nicko.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record DepositRequest(
        @NotNull @DecimalMin("1") @Digits(integer = 15, fraction = 0) BigDecimal amount,
        @NotBlank @Size(max = 20) @Pattern(regexp = "^\\+?(?:254[17][0-9]{8}|0[17][0-9]{8})$") String phoneNumber,
        @Size(max = 20) String description
) {
}
