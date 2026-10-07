package com.nicko.wallet.messaging;

import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.repository.WalletTransferRepository;
import com.nicko.wallet.service.WalletTransferExpirationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletTransferExpiryJobTests {
    @Mock WalletTransferRepository repository;
    @Mock WalletTransferExpirationService expirationService;

    @Test
    void expiresEveryTransferFromThePendingExpiredBatch() {
        WalletTransfer first = transfer();
        WalletTransfer second = transfer();
        when(repository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(WalletTransfer.Status.PENDING_STEPUP), any())).thenReturn(List.of(first, second));

        new WalletTransferExpiryJob(repository, expirationService).expirePendingTransfers();

        verify(expirationService).expire(first.getId());
        verify(expirationService).expire(second.getId());
    }

    @Test
    void doesNothingWhenThereAreNoExpiredTransfers() {
        when(repository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(WalletTransfer.Status.PENDING_STEPUP), any())).thenReturn(List.of());

        new WalletTransferExpiryJob(repository, expirationService).expirePendingTransfers();

        verifyNoInteractions(expirationService);
    }

    private static WalletTransfer transfer() {
        WalletTransfer transfer = new WalletTransfer();
        transfer.setId(UUID.randomUUID());
        return transfer;
    }
}
