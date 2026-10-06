package com.nicko.wallet.repository;

import com.nicko.wallet.entity.OutboxEvent;
import com.nicko.wallet.entity.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop100ByStatusAndEventTypeInOrderByCreatedAtAsc(OutboxStatus status,
                                                                          Collection<String> eventTypes);
}
