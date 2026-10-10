package com.nicko.payment.wallet;

import java.util.UUID;

public record WalletAccount(UUID walletId, UUID customerId, String currency) {
}
