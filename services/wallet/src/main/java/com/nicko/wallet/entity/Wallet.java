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
    @NotNull
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    private UUID customerId;

    @NotNull
    @Size(max = 20)
    @Column(name = "wallet_number", nullable = false, updatable = false, unique = true)
    private String walletNumber;

    @NotNull
    @ColumnDefault("'KES'")
    @Column(name = "currency", updatable = false, nullable = false)
    private String currency;

    @NotNull
    @ColumnDefault("0.0000")
    @Column(name = "balance", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @NotNull
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false, updatable = false)
    private BigInteger version;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ColumnDefault("now()")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

}
