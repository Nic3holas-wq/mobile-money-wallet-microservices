package com.nicko.payment.repository;

import com.nicko.payment.entity.PaymentCallback;
import com.nicko.payment.entity.enums.CallbackProcessingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCallbackRepository extends JpaRepository<PaymentCallback, UUID> {

    List<PaymentCallback> findTop25ByProcessingStatusAndRetryCountLessThanAndNextRetryAtBeforeOrderByReceivedAtAsc(
            CallbackProcessingStatus status, Integer retryCount, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentCallback c where c.id = :id")
    Optional<PaymentCallback> findByIdForUpdate(@Param("id") UUID id);
}
