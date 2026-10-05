package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import java.time.Instant;
import java.util.UUID;

public record OutboxEventResponse(UUID id, String aggregateType, UUID aggregateId, String eventType, UUID correlationId, OutboxStatus status, int attemptCount, Instant createdAt, Instant publishedAt) {}
