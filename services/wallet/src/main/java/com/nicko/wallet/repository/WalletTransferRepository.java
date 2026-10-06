package com.nicko.wallet.repository;

import com.nicko.wallet.entity.WalletTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

public interface WalletTransferRepository extends JpaRepository<WalletTransfer, UUID> {
    Optional<WalletTransfer> findBySourceWalletIdAndIdempotencyKey(UUID sourceWalletId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WalletTransfer t where t.id = :id")
    Optional<WalletTransfer> findByIdForUpdate(@Param("id") UUID id);

    List<WalletTransfer> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(WalletTransfer.Status status,
                                                                                 Instant before);
}
