package com.nicko.customer.mapper;

import com.nicko.customer.entity.OutboxEvent;
import com.nicko.customer.dto.OutboxEventResponse;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventMapper {
    public OutboxEventResponse toResponse(OutboxEvent entity) {
        return new OutboxEventResponse(
                entity.getId(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getCorrelationId(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getCreatedAt(),
                entity.getPublishedAt());
    }
}
