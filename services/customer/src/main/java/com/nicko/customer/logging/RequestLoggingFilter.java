package com.nicko.customer.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-Id";
    // Same rule as the gateway: rejects newlines and odd characters that could forge log lines
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && VALID_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            try {
                // Route templates avoid logging user-controlled paths, query strings or record IDs.
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = pattern == null ? "UNMATCHED" : pattern.toString();
                int status = failed ? 500 : response.getStatus();
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (status >= 500) {
                    log.error("event=http_completed method={} route={} status={} durationMs={}", request.getMethod(), route, status, duration);
                } else if (status >= 400) {
                    log.warn("event=http_completed method={} route={} status={} durationMs={}", request.getMethod(), route, status, duration);
                } else {
                    log.info("event=http_completed method={} route={} status={} durationMs={}", request.getMethod(), route, status, duration);
                }
            } finally {
                if (previous == null) { MDC.remove("requestId"); } else { MDC.put("requestId", previous); }
            }
        }
    }
}