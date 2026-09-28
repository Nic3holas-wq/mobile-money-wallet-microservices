package com.nicko.customer.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.ServletException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RequestLoggingFilterTests {
    @Test
    void correlatesLogsWithoutLoggingSensitivePathsAndRestoresMdc() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        events.start(); logger.addAppender(events);
        MDC.put("requestId", "previous");
        try {
            var request = new MockHttpServletRequest("GET", "/private-customer-id");
            request.setQueryString("token=private-token");
            request.addHeader("Authorization", "Bearer private-token");
            request.addHeader("X-Request-ID", "untrusted-id");
            var response = new MockHttpServletResponse();
            new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/customers/me/contacts/{id}");
                assertThat(MDC.get("requestId")).isNotEqualTo("previous");
                response.setStatus(404);
            });
            String id = response.getHeader("X-Request-ID");
            assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException();
            assertThat(MDC.get("requestId")).isEqualTo("previous");
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(id);
                assertThat(event.getFormattedMessage()).contains("status=404", "contacts/{id}")
                        .doesNotContain("private-customer-id", "private-token", "untrusted-id");
            });
        } finally { MDC.remove("requestId"); logger.detachAppender(events); events.stop(); }
    }

    @Test
    void cleansMdcAndPropagatesExceptions() {
        MDC.remove("requestId");
        var failure = new ServletException("private-details");
        assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(MDC.get("requestId")).isNull();
    }
}
