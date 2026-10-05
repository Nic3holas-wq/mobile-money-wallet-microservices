package com.nicko.customer.entity;

import com.nicko.customer.dto.RegisterCustomerRequest;
import com.nicko.customer.repository.*;
import com.nicko.customer.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CustomerAuditTransactionTests {
    @Autowired PlatformTransactionManager transactions;
    @Autowired CustomerService customers;
    @Autowired CustomerRepository repository;
    @Autowired CustomerAuditService audit;
    @Autowired OutboxEventService outbox;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private Customer customer(UUID actor) {
        var response = customers.register(actor, new RegisterCustomerRequest("Test", null, "Audit",
                LocalDate.of(1990, 1, 1), null, "KE", "en"));
        return repository.findById(response.id()).orElseThrow();
    }

    @Test
    void businessStateAuditAndEventRollbackTogether() {
        UUID actor = UUID.randomUUID();
        UUID[] customerId = new UUID[1];
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var customer = customer(actor);
            customerId[0] = customer.getId();
            audit.record(customer.getId(), actor, "TEST_CHANGE", "CUSTOMER", customer.getId(), Map.of(), Map.of("status", "PENDING"));
            outbox.record(customer, "customer.registered.v1", "CUSTOMER", customer.getId(), Map.of());
            em.flush();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_audit_record WHERE customer_id = ?", Integer.class, customer.getId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE customer_id = ?", Integer.class, customer.getId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT bool_and(a.correlation_id = o.correlation_id) FROM customer_audit_record a JOIN outbox_event o USING (customer_id) WHERE a.customer_id = ?", Boolean.class, customer.getId())).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(repository.existsByKeycloakUserId(actor)).isFalse();
        for (String table : new String[]{"customer_audit_record", "outbox_event"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE customer_id = ?", Integer.class, customerId[0])).isZero();
        }
    }

    @Test
    void databaseRejectsEditingOrDeletingHistory() {
        for (String sql : new String[]{"UPDATE customer_audit_record SET action = 'TAMPERED' WHERE customer_id = ?",
                "DELETE FROM customer_audit_record WHERE customer_id = ?"}) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                UUID actor = UUID.randomUUID();
                var customer = customer(actor);
                audit.record(customer.getId(), actor, "TEST_CHANGE", "CUSTOMER", customer.getId(), Map.of(), Map.of());
                em.flush();
                jdbc.update(sql, customer.getId());
            })).hasStackTraceContaining("Customer audit history is append-only");
        }
    }
}
