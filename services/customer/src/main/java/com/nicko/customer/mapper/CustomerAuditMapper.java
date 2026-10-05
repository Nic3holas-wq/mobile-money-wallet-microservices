package com.nicko.customer.mapper;

import com.nicko.customer.entity.CustomerAuditRecord;
import com.nicko.customer.dto.CustomerAuditResponse;
import org.springframework.stereotype.Component;

@Component
public class CustomerAuditMapper {
    public CustomerAuditResponse toResponse(CustomerAuditRecord record) {
        return new CustomerAuditResponse(record.getId(), record.getActorId(), record.getAction(),
                record.getTargetType(), record.getTargetId(), record.getOccurredAt(), record.getCorrelationId(),
                record.getBeforeState(), record.getAfterState());
    }
}
