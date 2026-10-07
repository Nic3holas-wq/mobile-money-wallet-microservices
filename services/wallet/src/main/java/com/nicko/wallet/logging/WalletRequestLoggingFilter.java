package com.nicko.wallet.logging;

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
public class WalletRequestLoggingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern WALLET_PIN_PATH = Pattern.compile("^/api/v1/wallets/[^/]+/pin/?$");
    private static final Pattern WALLET_TRANSFER_PATH = Pattern.compile("^/api/v1/wallets/[^/]+/transfers(?:/.*)?/?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String previousRequestId = MDC.get("requestId");
        String incomingRequestId = request.getHeader(REQUEST_ID_HEADER);
        String requestId = incomingRequestId != null && VALID_REQUEST_ID.matcher(incomingRequestId).matches()
                ? incomingRequestId : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                String route = routeTemplate(request);
                int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (status >= 500) {
                    log.error("event=http_completed method={} route={} status={} durationMs={}",
                            request.getMethod(), route, status, duration);
                } else if (status >= 400) {
                    log.warn("event=http_completed method={} route={} status={} durationMs={}",
                            request.getMethod(), route, status, duration);
                } else {
                    log.info("event=http_completed method={} route={} status={} durationMs={}",
                            request.getMethod(), route, status, duration);
                }
            } finally {
                if (previousRequestId == null) {
                    MDC.remove("requestId");
                } else {
                    MDC.put("requestId", previousRequestId);
                }
            }
        }
    }

    private String routeTemplate(HttpServletRequest request) {
        Object matchedPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matchedPattern != null) {
            return matchedPattern.toString();
        }
        String path = request.getRequestURI();
        if (WALLET_PIN_PATH.matcher(path).matches()) {
            return "/api/v1/wallets/{walletId}/pin";
        }
        if (WALLET_TRANSFER_PATH.matcher(path).matches()) {
            return "/api/v1/wallets/{walletId}/transfers/**";
        }
        if (path.startsWith("/api/v1/wallets/")) {
            return "/api/v1/wallets/**";
        }
        return "UNMATCHED";
    }
}
