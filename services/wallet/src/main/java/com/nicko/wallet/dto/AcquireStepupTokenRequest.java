package com.nicko.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AcquireStepupTokenRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String pin) {
}
