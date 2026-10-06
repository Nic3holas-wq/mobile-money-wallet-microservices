package com.nicko.wallet.dto;

import com.nicko.wallet.entity.WalletPaymentOperation;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentOperationResponse(
        UUID operationId,
        UUID walletId,
        String paymentReference,
        WalletPaymentOperation.Kind kind,
        WalletPaymentOperation.State state,
        BigDecimal amount,
        String currency,
        BigDecimal balance,
        BigDecimal reservedBalance,
        BigDecimal availableBalance
) {
}
