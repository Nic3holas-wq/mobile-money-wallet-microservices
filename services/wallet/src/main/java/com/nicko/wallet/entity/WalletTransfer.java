package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_transfer", uniqueConstraints = {
        @UniqueConstraint(name = "uc_transfer_wallet_idem",
                columnNames = {"source_wallet_id", "idempotency_key"})
}, indexes = {
        @Index(name = "idx_transfer_source_wallet", columnList = "source_wallet_id, created_at"),
        @Index(name = "idx_transfer_destination_wallet", columnList = "destination_wallet_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class WalletTransfer {

    public enum Status { PENDING_STEPUP, PROCESSING, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Size(max = 50)
    @NotNull
    @Column(name = "reference", nullable = false, updatable = false, unique = true, length = 50)
    private String reference;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_wallet_id", nullable = false, updatable = false)
    private Wallet sourceWallet;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_wallet_id", nullable = false, updatable = false)
    private Wallet destinationWallet;

    @NotNull
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Size(min = 3, max = 3)
    @NotNull
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING_STEPUP;

    @Size(max = 100)
    @NotNull
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "description")
    private String description;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
