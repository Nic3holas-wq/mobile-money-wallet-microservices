package com.nicko.wallet.dto;

import jakarta.validation.constraints.NotBlank;

public record CompleteWalletTransferRequest(@NotBlank String stepupToken) {
}
