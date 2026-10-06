package com.nicko.wallet.repository;

import com.nicko.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    boolean existsByCustomerId(UUID customerId);

    Optional<Wallet> findByCustomerId(UUID customerId);

    Optional<Wallet> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.publicId = :publicId")
    Optional<Wallet> findByPublicIdForUpdate(@Param("publicId") UUID publicId);
}
