package com.nicko.customer.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nicko.customer.config.DocumentProtection;
import com.nicko.customer.dto.RegisterCustomerRequest;
import com.nicko.customer.mapper.CustomerMapper;
import com.nicko.customer.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LoggingCoverageTests {
    @Test void logsMockRepositoryMethodsMapperAndDocumentProtectionWithoutSensitiveValues() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
            Level previous = logger.getLevel();
            var events = new ListAppender<ILoggingEvent>();
            events.start();
            logger.addAppender(events);
            logger.setLevel(Level.DEBUG);
            try {
                CustomerRepository repository = context.getBean(CustomerRepository.class);
                repository.count();
                repository.findByKeycloakUserId(UUID.randomUUID());
                context.getBean(CustomerMapper.class).toEntity(new RegisterCustomerRequest(
                        "private-name", null, "private-name", LocalDate.of(1990, 1, 1), null, "KE", "en"));
                context.getBean(DocumentProtection.class).normalize("private-document");
                assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                        assertThat(message).contains("layer=repository", "count()", "event=completed"));
                assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                        assertThat(message).contains("layer=repository", "findByKeycloakUserId", "event=completed"));
                assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                        assertThat(message).contains("layer=mapper", "toEntity", "event=completed"));
                assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(message ->
                        assertThat(message).contains("layer=security", "normalize", "event=completed"));
                assertThat(events.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain("private-name", "private-document"));
            } finally {
                logger.setLevel(previous);
                logger.detachAppender(events);
                events.stop();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean LoggingAspect loggingAspect() { return new LoggingAspect(); }
        @Bean CustomerRepository customerRepository() { return mock(CustomerRepository.class); }
        @Bean CustomerMapper customerMapper() { return new CustomerMapper(); }
        @Bean DocumentProtection documentProtection() { return new DocumentProtection("", ""); }
    }
}
