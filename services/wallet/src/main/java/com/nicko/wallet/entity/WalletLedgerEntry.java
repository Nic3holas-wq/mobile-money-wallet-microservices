package com.nicko.wallet.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name= "wallet_ledger_entry", indexes = {
        @Index(name = "idx_wallet_ledger_entry_wallet_id", columnList = "wallet_id", unique = true),
        @Index(name = "idx_wallet_ledger_entry_transfer_id", columnList = "transfer_id", unique = true),
        @Index(name = "idx_wallet_ledger_entry_created_at", columnList = "created_at", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class WalletLedgerEntry {
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
    @JoinColumn(name= "transfer_id", nullable = false, updatable = false)
    private WalletTransfer walletTransfer;

    @NotNull
    @Column(name = "entry_type", nullable = false, updatable = false, length = 10)
    private String entryType;

    @NotNull
    @Column(name = "direction", nullable = false, updatable = false)
    private String direction;

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
    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "description", nullable = true)
    private String description;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
