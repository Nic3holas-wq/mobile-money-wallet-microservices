package com.nicko.wallet.messaging;

import com.nicko.wallet.entity.OutboxEvent;
import com.nicko.wallet.entity.enums.OutboxStatus;
import com.nicko.wallet.event.WalletEventTypes;
import com.nicko.wallet.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletOutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.topics.wallet-created}")
    private String walletCreatedTopic;

    @Value("${app.kafka.topics.transfer-completed}")
    private String transferCompletedTopic;

    @Value("${app.kafka.topics.transfer-failed}")
    private String transferFailedTopic;

    @Value("${app.kafka.topics.transfer-reversed:wallet.transfer.reversed.v1}")
    private String transferReversedTopic;

    @Scheduled(fixedDelayString = "${app.kafka.outbox-poll-interval:PT1S}")
    @Transactional
    public void publishPendingEvents() {
        for (OutboxEvent event : outboxEventRepository.findTop100ByStatusAndEventTypeInOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                List.of(WalletEventTypes.WALLET_CREATED, WalletEventTypes.WALLET_TRANSFER_COMPLETED,
                        WalletEventTypes.WALLET_TRANSFER_FAILED, WalletEventTypes.WALLET_TRANSFER_REVERSED))) {
            String topic = topicFor(event.getEventType());
            if (topic == null) {
                continue;
            }
            try {
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception exception) {
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setLastError(exception.getMessage());
                log.error("Failed to publish wallet outbox event {}", event.getId(), exception);
            }
        }
    }

    private String topicFor(String eventType) {
        if (WalletEventTypes.WALLET_CREATED.equals(eventType)) {
            return walletCreatedTopic;
        }
        if (WalletEventTypes.WALLET_TRANSFER_COMPLETED.equals(eventType)) {
            return transferCompletedTopic;
        }
        if (WalletEventTypes.WALLET_TRANSFER_FAILED.equals(eventType)) {
            return transferFailedTopic;
        }
        if (WalletEventTypes.WALLET_TRANSFER_REVERSED.equals(eventType)) {
            return transferReversedTopic;
        }
        return null;
    }
}
