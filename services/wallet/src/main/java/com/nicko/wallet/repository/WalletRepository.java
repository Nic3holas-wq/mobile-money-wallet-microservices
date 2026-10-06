package com.nicko.wallet.repository;

import com.nicko.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    boolean existsByCustomerId(UUID customerId);

    Optional<Wallet> findByCustomerId(UUID customerId);

    Optional<Wallet> findByPublicId(UUID publicId);
}