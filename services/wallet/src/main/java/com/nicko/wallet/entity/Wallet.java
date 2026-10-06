package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import com.nicko.wallet.entity.enums.WalletStatus;

@Entity
@Table(name = "wallet", indexes = {
        @Index(name = "idx_wallet_customer_id", columnList = "customer_id", unique = true),
        @Index(name = "idx_wallet_public_id", columnList = "public_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor

public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @NotNull
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @NotNull
    @Size(max = 20)
    @Column(name = "wallet_number",
            nullable = false,
            updatable = false,
            unique = true)
    private String walletNumber;

    @NotNull
    @ColumnDefault("'KES'")
    @Column(name = "currency",
            nullable = false,
            updatable = false)
    private String currency;

    @NotNull
    @ColumnDefault("0.0000")
    @Column(name = "balance",
            nullable = false,
            precision = 19,
            scale = 4)
    private BigDecimal balance;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    @NotNull
    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedBalance = BigDecimal.ZERO;

    @NotNull
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private Long version;

    @NotNull
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
