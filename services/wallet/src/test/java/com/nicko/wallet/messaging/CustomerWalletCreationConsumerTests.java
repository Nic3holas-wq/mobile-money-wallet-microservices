package com.nicko.wallet.messaging;

import com.nicko.wallet.event.CustomerWalletCreationRequestedEvent;
import com.nicko.wallet.service.BusinessCorrelation;
import com.nicko.wallet.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerWalletCreationConsumerTests {
    @Mock WalletService walletService;

    @AfterEach
    void cleanupCorrelation() {
        BusinessCorrelation.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void delegatesWalletCreationAndSetsCorrelationForEvent() {
        UUID correlationId = UUID.randomUUID();
        CustomerWalletCreationRequestedEvent event = new CustomerWalletCreationRequestedEvent(
                UUID.randomUUID(), "CUSTOMER_WALLET_CREATION_REQUESTED", 1, "2026-10-07T00:00:00Z",
                UUID.randomUUID(), correlationId, Map.of("firstName", "Test"));

        new CustomerWalletCreationConsumer(walletService).consume(event);

        verify(walletService).createWallet(event);
    }

    @Test
    void delegatesEventWithNoCorrelationId() {
        CustomerWalletCreationRequestedEvent event = new CustomerWalletCreationRequestedEvent(
                UUID.randomUUID(), "CUSTOMER_WALLET_CREATION_REQUESTED", 1, "2026-10-07T00:00:00Z",
                UUID.randomUUID(), null, Map.of());

        new CustomerWalletCreationConsumer(walletService).consume(event);

        verify(walletService).createWallet(event);
    }
}
