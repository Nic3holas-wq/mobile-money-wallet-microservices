package com.nicko.customer.mapper;

import com.nicko.customer.entity.CustomerLimit;
import com.nicko.customer.dto.CustomerLimitResponse;
import com.nicko.customer.dto.CustomerLimitRequest;
import org.springframework.stereotype.Component;

@Component
public class CustomerLimitMapper {
    public void update(CustomerLimit entity, CustomerLimitRequest request) {
        entity.setTransactionType(request.transactionType());
        entity.setCurrency(request.currency());
        entity.setPerTransactionLimit(request.perTransactionLimit());
        entity.setDailyLimit(request.dailyLimit());
        entity.setMonthlyLimit(request.monthlyLimit());
        entity.setDailyCountLimit(request.dailyCountLimit());
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveUntil(request.effectiveUntil());
        entity.setReason(request.reason().strip());
    }

    public CustomerLimitResponse toResponse(CustomerLimit entity) {
        return new CustomerLimitResponse(
                entity.getId(),
                entity.getTransactionType(),
                entity.getCurrency(),
                entity.getPerTransactionLimit(),
                entity.getDailyLimit(),
                entity.getMonthlyLimit(),
                entity.getDailyCountLimit(),
                entity.getEffectiveFrom(),
                entity.getEffectiveUntil(),
                entity.getReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
