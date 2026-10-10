package com.nicko.wallet.dto;

import java.util.UUID;

public record PaymentWalletAccountResponse(UUID walletId, UUID customerId, String currency) {
}
