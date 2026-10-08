package com.nicko.payment.entity;

import com.nicko.payment.entity.enums.PaymentProvider;
import com.nicko.payment.entity.enums.PaymentStatus;
import com.nicko.payment.entity.enums.PaymentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uc_payment_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uc_payment_reference", columnNames = "reference"),
        @UniqueConstraint(name = "uc_payment_customer_idempotency", columnNames = {"customer_id", "idempotency_key"})
}, indexes = {
        @Index(name = "idx_payment_customer_created", columnList = "customer_id, created_at"),
        @Index(name = "idx_payment_status_created", columnList = "status, created_at"),
        @Index(name = "idx_payment_wallet", columnList = "wallet_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @NotNull
    @Column(name = "reference", nullable = false, updatable = false, length = 50)
    private String reference;

    @NotNull
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @NotNull
    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private PaymentType type;

    @NotNull
    @DecimalMin(value = "0.0001")
    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @NotNull
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @NotNull
    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "description", length = 255)
    private String description;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "payment", fetch = FetchType.LAZY)
    private List<PaymentAttempt> attempts = new ArrayList<>();

    @OneToMany(mappedBy = "payment", fetch = FetchType.LAZY)
    private List<PaymentCallback> callbacks = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
