package com.nicko.wallet.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CustomerWalletCreationRequestedEvent(

        UUID eventId,

        String eventType,

        Integer schemaVersion,

        Instant occurredAt,

        UUID customerId,

        UUID correlationId,

        Map<String, Object> data

) {
}