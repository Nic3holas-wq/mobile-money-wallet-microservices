package com.nicko.wallet.service;

import com.nicko.wallet.entity.OutboxEvent;
import com.nicko.wallet.entity.Wallet;
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
}
