package com.nicko.customer.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nicko.customer.config.SecurityErrorHandler;
import com.nicko.customer.controller.CustomerController;
import com.nicko.customer.dto.CustomerResponse;
import com.nicko.customer.service.CustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoggingAspectTests {
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restore() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        events.stop();
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new LoggingAspect());
        return factory.getProxy();
    }

    @Test
    void preservesResponseAndLogsTimingWithoutTokenOrCustomerData() {
        UUID user = UUID.randomUUID();
        CustomerService service = mock(CustomerService.class);
        CustomerResponse response = new CustomerResponse(UUID.randomUUID(), "private-customer-number",
                "private-first-name", null, "private-last-name", null, null, "KE", "en",
                null, null, null, false, null);
        when(service.getCurrent(user)).thenReturn(response);
        CustomerController controller = proxy(new CustomerController(service));
        Jwt token = Jwt.withTokenValue("secret-access-token").header("alg", "RS256").subject(user.toString()).build();
        assertThat(controller.getCurrent(token)).isSameAs(response);
        verify(service, times(1)).getCurrent(user);
        assertThat(events.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("CustomerController.getCurrent", "status=200", "durationMs=");
        });
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("secret-access-token", "private-", user.toString());
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void propagatesOriginalExceptionWithoutLoggingItsSensitiveMessage() {
        UUID user = UUID.randomUUID();
        CustomerService service = mock(CustomerService.class);
        var failure = new ResponseStatusException(HttpStatus.NOT_FOUND, "private-personal-information");
        when(service.getCurrent(user)).thenThrow(failure);
        CustomerController controller = proxy(new CustomerController(service));
        Jwt token = Jwt.withTokenValue("secret-access-token").header("alg", "RS256").subject(user.toString()).build();
        assertThatThrownBy(() -> controller.getCurrent(token)).isSameAs(failure);
        assertThat(events.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=rejected", "status=404", "durationMs=");
        });
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("private-personal-information", "secret-access-token");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void logsSecurityDenialsAndPreservesProblemResponses() throws Exception {
        SecurityErrorHandler handler = proxy(new SecurityErrorHandler(JsonMapper.builder().build()));
        var request = new MockHttpServletRequest("GET", "/api/v1/customers/me");
        request.addHeader("Authorization", "Bearer secret-access-token");
        var unauthorized = new MockHttpServletResponse();
        handler.commence(request, unauthorized, new BadCredentialsException("secret-access-token"));
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        var forbidden = new MockHttpServletResponse();
        handler.handle(request, forbidden, new AccessDeniedException("private-personal-information"));
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("event=authentication_rejected status=401", "event=access_denied status=403");
        assertThat(events.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("secret-access-token", "private-personal-information"));
    }
}
