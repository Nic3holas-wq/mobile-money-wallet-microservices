package com.nicko.wallet.service;

import com.nicko.wallet.entity.OutboxEvent;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.entity.enums.OutboxStatus;
import com.nicko.wallet.event.WalletEventTypes;
import com.nicko.wallet.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletOutboxEventServiceTests {

    @Mock OutboxEventRepository repository;
    private WalletOutboxEventService service;

    @BeforeEach
    void setUp() {
        service = new WalletOutboxEventService(repository);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            if (event.getId() == null) event.setId(UUID.randomUUID());
            return event;
        });
    }

    @Test
    void recordsWalletCreatedEventWithWalletMetadata() {
        Wallet wallet = wallet();

        service.recordWalletCreated(wallet);

        OutboxEvent event = savedEvent();
        assertThat(event.getCustomerId()).isEqualTo(wallet.getCustomerId());
        assertThat(event.getAggregateType()).isEqualTo("WALLET");
        assertThat(event.getAggregateId()).isEqualTo(wallet.getId());
        assertThat(event.getEventType()).isEqualTo(WalletEventTypes.WALLET_CREATED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getCorrelationId()).isNotNull();
        assertThat(event.getPayload()).containsEntry("eventId", event.getId().toString())
                .containsEntry("eventType", WalletEventTypes.WALLET_CREATED)
                .containsEntry("schemaVersion", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
        assertThat(data).containsEntry("walletId", wallet.getId().toString())
                .containsEntry("publicId", wallet.getPublicId().toString())
                .containsEntry("walletNumber", wallet.getWalletNumber())
                .containsEntry("currency", wallet.getCurrency());
    }

    @Test
    void recordsCompletedAndFailedTransferEvents() {
        WalletTransfer transfer = transfer();

        service.recordWalletTransferCompleted(transfer);
        OutboxEvent completed = savedEvent();
        assertThat(completed.getEventType()).isEqualTo(WalletEventTypes.WALLET_TRANSFER_COMPLETED);
        assertThat(completed.getPayload()).containsEntry("eventType", WalletEventTypes.WALLET_TRANSFER_COMPLETED);
        @SuppressWarnings("unchecked")
        Map<String, Object> completedData = (Map<String, Object>) completed.getPayload().get("data");
        assertThat(completedData).containsEntry("transferId", transfer.getId().toString())
                .containsEntry("reference", transfer.getReference())
                .containsEntry("amount", transfer.getAmount())
                .containsEntry("currency", transfer.getCurrency());

        service.recordWalletTransferFailed(transfer);
        OutboxEvent failed = savedEvent();
        assertThat(failed.getEventType()).isEqualTo(WalletEventTypes.WALLET_TRANSFER_FAILED);
        assertThat(failed.getPayload()).containsEntry("eventType", WalletEventTypes.WALLET_TRANSFER_FAILED);
        assertThat(failed.getAggregateId()).isEqualTo(transfer.getId());
    }

    private OutboxEvent savedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private Wallet wallet() {
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setPublicId(UUID.randomUUID());
        wallet.setCustomerId(UUID.randomUUID());
        wallet.setWalletNumber("129123456789");
        wallet.setCurrency("KES");
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setReservedBalance(BigDecimal.ZERO);
        return wallet;
    }

    private WalletTransfer transfer() {
        WalletTransfer transfer = new WalletTransfer();
        transfer.setId(UUID.randomUUID());
        transfer.setReference("ref-123");
        transfer.setSourceWallet(wallet());
        transfer.setDestinationWallet(wallet());
        transfer.setAmount(new BigDecimal("12.50"));
        transfer.setCurrency("KES");
        transfer.setStatus(WalletTransfer.Status.FAILED);
        return transfer;
    }
}
