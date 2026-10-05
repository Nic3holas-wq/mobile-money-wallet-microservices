package com.nicko.customer.repository;

import com.nicko.customer.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    Page<OutboxEvent> findByCustomerId(UUID customerId, Pageable pageable);
    Optional<OutboxEvent> findByIdAndCustomerId(UUID id, UUID customerId);
}
