package com.nicko.payment.messaging;

import com.nicko.payment.entity.OutboxEvent;
import com.nicko.payment.entity.enums.OutboxStatus;
import com.nicko.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
public class PaymentOutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    @org.springframework.beans.factory.annotation.Value("${app.kafka.topics.payment-events}")
    private String topic;

    @Scheduled(fixedDelayString = "${app.kafka.outbox-poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxEvent> events = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : events) {
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", event.getId().toString());
                envelope.put("eventType", event.getEventType());
                envelope.put("aggregateType", event.getAggregateType());
                envelope.put("aggregateId", event.getAggregateId().toString());
                envelope.put("correlationId", event.getCorrelationId().toString());
                envelope.put("occurredAt", event.getCreatedAt().toString());
                envelope.put("payload", event.getPayload());
                kafkaTemplate.send(topic, event.getAggregateId().toString(), envelope).get(10, TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(java.time.Instant.now());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setLastError(exception.getClass().getSimpleName());
            }
        }
        outboxRepository.saveAll(events);
    }
}
