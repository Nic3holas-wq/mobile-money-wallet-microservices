package com.nicko.wallet.repository;

import com.nicko.wallet.entity.WalletLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalletLedgerEntryRepository extends JpaRepository<WalletLedgerEntry, UUID> {
}
