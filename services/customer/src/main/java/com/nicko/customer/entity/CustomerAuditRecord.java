package com.nicko.customer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "customer_audit_record")
@Immutable
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class CustomerAuditRecord {
    @Id private UUID id;
    @Column(nullable = false) private UUID customerId;
    @Column(nullable = false) private UUID actorId;
    @Column(nullable = false, length = 100) private String action;
    @Column(nullable = false, length = 100) private String targetType;
    @Column(nullable = false) private UUID targetId;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false) private UUID correlationId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> beforeState;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> afterState;

    public CustomerAuditRecord(UUID customerId, UUID actorId, String action, String targetType,
            UUID targetId, UUID correlationId, Map<String, Object> before, Map<String, Object> after) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.correlationId = correlationId;
        this.occurredAt = Instant.now();
        this.beforeState = Map.copyOf(before);
        this.afterState = Map.copyOf(after);
    }
}
