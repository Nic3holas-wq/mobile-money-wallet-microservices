package com.nicko.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetWalletPinRequest(
        @NotBlank @Pattern(regexp = "[0-9]{6}") String newPin,
        @Pattern(regexp = "[0-9]{6}") String currentPin
) {
}
