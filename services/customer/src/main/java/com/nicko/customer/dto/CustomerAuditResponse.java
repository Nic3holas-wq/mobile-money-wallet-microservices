package com.nicko.customer.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CustomerAuditResponse(UUID id, UUID actorId, String action, String targetType,
        UUID targetId, Instant occurredAt, UUID correlationId,
        Map<String, Object> beforeState, Map<String, Object> afterState) {}
