package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "wallet_ledger_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uc_ledger_transfer_wallet_direction",
                columnNames = {"transfer_id", "wallet_id", "direction"}),
        indexes = {
                @Index(name = "idx_ledger_wallet_created", columnList = "wallet_id, created_at"),
                @Index(name = "idx_ledger_transfer_id", columnList = "transfer_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class WalletLedgerEntry {

    public enum Direction { DEBIT, CREDIT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private WalletTransfer walletTransfer;

    @NotNull
    @Column(name = "entry_type", nullable = false, updatable = false, length = 10)
    private String entryType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 6)
    private Direction direction;

    @NotNull
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @NotNull
    @Column(name = "balance_before", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceBefore;

    @NotNull
    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @NotNull
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "description")
    private String description;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}