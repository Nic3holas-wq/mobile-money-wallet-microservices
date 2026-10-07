package com.nicko.wallet.service;

import com.nicko.wallet.entity.OutboxEvent;
import com.nicko.wallet.entity.Wallet;
import com.nicko.wallet.entity.WalletTransfer;
import com.nicko.wallet.entity.enums.OutboxStatus;
import com.nicko.wallet.event.WalletEventTypes;
import com.nicko.wallet.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WalletOutboxEventService {

    private final OutboxEventRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWalletCreated(Wallet wallet) {

        var event = new OutboxEvent();

        event.setCustomerId(wallet.getCustomerId());
        event.setAggregateType("WALLET");
        event.setAggregateId(wallet.getId());
        event.setEventType(WalletEventTypes.WALLET_CREATED);
        event.setCorrelationId(BusinessCorrelation.current());
        event.setStatus(OutboxStatus.PENDING);
        event.setAttemptCount(0);

        /*
         * Persist first so Hibernate generates the event ID.
         */
        event.setPayload(Map.of());

        repository.save(event);

        /*
         * The event ID is now available and can be included
         * in the Kafka payload.
         */
        event.setPayload(
                Map.of(
                        "eventId", event.getId().toString(),
                        "eventType", WalletEventTypes.WALLET_CREATED,
                        "schemaVersion", 1,
                        "occurredAt", Instant.now().toString(),
                        "customerId", wallet.getCustomerId().toString(),
                        "correlationId",
                        event.getCorrelationId().toString(),
                        "data",
                        Map.of(
                                "walletId", wallet.getId().toString(),
                                "publicId", wallet.getPublicId().toString(),
                                "customerId",
                                wallet.getCustomerId().toString(),
                                "walletNumber",
                                wallet.getWalletNumber(),
                                "currency",
                                wallet.getCurrency()
                        )
                )
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWalletTransferCompleted(WalletTransfer transfer) {
        recordWalletTransfer(transfer, WalletEventTypes.WALLET_TRANSFER_COMPLETED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWalletTransferFailed(WalletTransfer transfer) {
        recordWalletTransfer(transfer, WalletEventTypes.WALLET_TRANSFER_FAILED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWalletTransferReversed(WalletTransfer original, WalletTransfer reversal,
                                             String adminSubject, String reason) {
        var event = new OutboxEvent();
        event.setCustomerId(original.getSourceWallet().getCustomerId());
        event.setAggregateType("WALLET_TRANSFER");
        event.setAggregateId(original.getId());
        event.setEventType(WalletEventTypes.WALLET_TRANSFER_REVERSED);
        event.setCorrelationId(BusinessCorrelation.current());
        event.setStatus(OutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setPayload(Map.of());
        repository.save(event);
        event.setPayload(Map.of(
                "eventId", event.getId().toString(),
                "eventType", WalletEventTypes.WALLET_TRANSFER_REVERSED,
                "schemaVersion", 1,
                "occurredAt", Instant.now().toString(),
                "customerId", original.getSourceWallet().getCustomerId().toString(),
                "correlationId", event.getCorrelationId().toString(),
                "data", Map.of(
                        "originalTransferId", original.getId().toString(),
                        "reversalTransferId", reversal.getId().toString(),
                        "sourceWalletId", reversal.getSourceWallet().getPublicId().toString(),
                        "destinationWalletId", reversal.getDestinationWallet().getPublicId().toString(),
                        "amount", reversal.getAmount(),
                        "currency", reversal.getCurrency(),
                        "adminSubject", adminSubject,
                        "reason", reason
                )
        ));
    }

    private void recordWalletTransfer(WalletTransfer transfer, String eventType) {
        var event = new OutboxEvent();
        event.setCustomerId(transfer.getSourceWallet().getCustomerId());
        event.setAggregateType("WALLET_TRANSFER");
        event.setAggregateId(transfer.getId());
        event.setEventType(eventType);
        event.setCorrelationId(BusinessCorrelation.current());
        event.setStatus(OutboxStatus.PENDING);
        event.setAttemptCount(0);
        event.setPayload(Map.of());
        repository.save(event);
        event.setPayload(Map.of(
                "eventId", event.getId().toString(),
                "eventType", eventType,
                "schemaVersion", 1,
                "occurredAt", Instant.now().toString(),
                "customerId", transfer.getSourceWallet().getCustomerId().toString(),
                "correlationId", event.getCorrelationId().toString(),
                "data", Map.of(
                        "transferId", transfer.getId().toString(),
                        "reference", transfer.getReference(),
                        "sourceWalletId", transfer.getSourceWallet().getPublicId().toString(),
                        "destinationWalletId", transfer.getDestinationWallet().getPublicId().toString(),
                        "sourceCustomerId", transfer.getSourceWallet().getCustomerId().toString(),
                        "destinationCustomerId", transfer.getDestinationWallet().getCustomerId().toString(),
                        "amount", transfer.getAmount(),
                        "currency", transfer.getCurrency(),
                        "status", transfer.getStatus().name()
                )
        ));
    }
}
