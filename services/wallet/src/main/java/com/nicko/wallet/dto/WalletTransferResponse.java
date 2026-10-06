package com.nicko.wallet.dto;

import com.nicko.wallet.entity.WalletTransfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletTransferResponse(
        UUID transferId,
        String reference,
        UUID sourceWalletId,
        UUID destinationWalletId,
        BigDecimal amount,
        String currency,
        WalletTransfer.Status status,
        BigDecimal sourceBalance,
        BigDecimal sourceReservedBalance,
        BigDecimal sourceAvailableBalance,
        BigDecimal destinationBalance,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt
) {
}
