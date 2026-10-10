package com.nicko.payment.wallet;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletCreditResponse(UUID operationId, UUID walletId, String paymentReference,
                                  String kind, String state, BigDecimal amount, String currency,
                                  BigDecimal balance, BigDecimal reservedBalance, BigDecimal availableBalance) {
}
