package com.nicko.payment.entity;

import com.nicko.payment.entity.enums.CallbackProcessingStatus;
import com.nicko.payment.entity.enums.PaymentProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "payment_callback", indexes = {
        @Index(name = "idx_payment_callback_payment_received", columnList = "payment_id, received_at"),
        @Index(name = "idx_payment_callback_status_received", columnList = "processing_status, received_at"),
        @Index(name = "idx_payment_callback_provider_reference", columnList = "provider, provider_reference")
})
@Getter
@Setter
@NoArgsConstructor
public class PaymentCallback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20, updatable = false)
    private PaymentProvider provider;

    @NotNull
    @Column(name = "callback_type", nullable = false, length = 50, updatable = false)
    private String callbackType;

    @Column(name = "provider_reference", length = 150, updatable = false)
    private String providerReference;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;

    @NotNull
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private CallbackProcessingStatus processingStatus;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @NotNull
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
}
