package com.nicko.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseWalletTransferRequest(@NotBlank @Size(max = 255) String reason) {
}
