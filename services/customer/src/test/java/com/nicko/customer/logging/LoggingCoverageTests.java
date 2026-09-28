package com.nicko.customer.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nicko.customer.repository.CustomerRepository;
import com.nicko.customer.mapper.CustomerMapper;
import com.nicko.customer.dto.RegisterCustomerRequest;
import com.nicko.customer.config.DocumentProtection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.slf4j.LoggerFactory;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class LoggingCoverageTests {
    @Autowired CustomerRepository repository;
    @Autowired CustomerMapper mapper;
    @Autowired DocumentProtection protection;

    @Test
    void logsInheritedAndCustomRepositoryMethodsMappersAndProtectionWithoutData() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
        Level old = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events); logger.setLevel(Level.DEBUG);
        try {
            repository.count();
            repository.findByKeycloakUserId(UUID.randomUUID());
            mapper.toEntity(new RegisterCustomerRequest("private-name", null, "private-name", LocalDate.of(1990,1,1), null, "KE", "en"));
            protection.normalize("private-document");
            assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                    assertThat(message).contains("layer=repository", "count()", "event=completed"));
            assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                    assertThat(message).contains("layer=repository", "findByKeycloakUserId", "event=completed"));
            assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                    assertThat(message).contains("layer=mapper", "toEntity", "event=completed"));
            assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                    assertThat(message).contains("layer=security", "normalize", "event=completed"));
            assertThat(events.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("private-name", "private-document"));
        } finally { logger.setLevel(old); logger.detachAppender(events); events.stop(); }
    }
}
