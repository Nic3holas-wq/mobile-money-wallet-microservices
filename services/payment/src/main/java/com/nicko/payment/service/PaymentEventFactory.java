package com.nicko.payment.service;

import com.nicko.payment.entity.OutboxEvent;
import com.nicko.payment.entity.Payment;
import com.nicko.payment.entity.enums.OutboxStatus;
import com.nicko.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PaymentEventFactory {

    private final OutboxEventRepository outboxEventRepository;

    public void record(Payment payment, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getPublicId().toString());
        payload.put("reference", payment.getReference());
        payload.put("customerId", payment.getCustomerId().toString());
        payload.put("walletId", payment.getWalletId().toString());
        payload.put("type", payment.getType().name());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("status", payment.getStatus().name());

        OutboxEvent event = new OutboxEvent();
        event.setCustomerId(payment.getCustomerId());
        event.setAggregateType("PAYMENT");
        event.setAggregateId(payment.getId());
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCorrelationId(payment.getPublicId());
        event.setStatus(OutboxStatus.PENDING);
        event.setAttemptCount(0);
        outboxEventRepository.save(event);
    }
}
