package com.nicko.customer.service;

import com.nicko.customer.entity.OutboxEvent;
import com.nicko.customer.entity.enums.OutboxStatus;
import com.nicko.customer.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletCreationOutboxPublisher {

    private static final String EVENT_TYPE = "customer.wallet.creation.requested.v1";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.wallet-creation-requested}")
    private String topic;

    @Scheduled(fixedDelayString = "${app.kafka.outbox-poll-interval:PT1S}")
    @Transactional
    public void publishPendingWalletCreationRequests() {
        var events = outboxEventRepository.findTop100ByEventTypeAndStatusOrderByCreatedAtAsc(
                EVENT_TYPE, OutboxStatus.PENDING);

        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception exception) {
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setLastError(exception.getMessage());
                log.error("Failed to publish wallet creation request outbox event {}", event.getId(), exception);
            }
        }
    }
}
