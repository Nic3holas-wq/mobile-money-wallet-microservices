package com.nicko.wallet.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
@Order(0)
public class WalletLoggingAspect {

    @Around("execution(public * com.nicko.wallet.controller..*(..))")
    public Object logController(ProceedingJoinPoint invocation) throws Throwable {
        return logOperation(invocation, "controller");
    }

    @Around("execution(public * com.nicko.wallet.service..*(..))")
    public Object logService(ProceedingJoinPoint invocation) throws Throwable {
        return logOperation(invocation, "service");
    }

    @Around("this(org.springframework.data.repository.Repository) && bean(*Repository) && execution(public * *(..))")
    public Object logRepository(ProceedingJoinPoint invocation) throws Throwable {
        return logOperation(invocation, "repository");
    }

    @Around("execution(public * com.nicko.wallet.customer..*(..))")
    public Object logCustomerClient(ProceedingJoinPoint invocation) throws Throwable {
        return logOperation(invocation, "customer-client");
    }

    private Object logOperation(ProceedingJoinPoint invocation, String layer) throws Throwable {
        boolean controller = "controller".equals(layer);
        String operation = invocation.getSignature().toShortString();
        long started = System.nanoTime();
        log.debug("layer={} operation={} event=started", layer, operation);
        try {
            Object result = invocation.proceed();
            long duration = elapsedMillis(started);
            if (controller) {
                int status = result instanceof ResponseEntity<?> response
                        ? response.getStatusCode().value() : 200;
                log.info("operation={} event=completed status={} durationMs={}", operation, status, duration);
            } else {
                log.debug("layer={} operation={} event=completed durationMs={}", layer, operation, duration);
            }
            return result;
        } catch (Throwable failure) {
            String errorType = failure.getClass().getSimpleName();
            long duration = elapsedMillis(started);
            if (failure instanceof ResponseStatusException status && status.getStatusCode().is4xxClientError()) {
                if (controller) {
                    log.warn("operation={} event=rejected status={} errorType={} durationMs={}",
                            operation, status.getStatusCode().value(), errorType, duration);
                } else {
                    log.debug("layer={} operation={} event=rejected status={} errorType={} durationMs={}",
                            layer, operation, status.getStatusCode().value(), errorType, duration);
                }
            } else if (failure instanceof ResponseStatusException status) {
                if (controller) {
                    log.error("operation={} event=failed status={} errorType={} durationMs={}",
                            operation, status.getStatusCode().value(), errorType, duration);
                } else {
                    log.debug("layer={} operation={} event=failed status={} errorType={} durationMs={}",
                            layer, operation, status.getStatusCode().value(), errorType, duration);
                }
            } else if (controller) {
                log.error("operation={} event=failed errorType={} durationMs={}",
                        operation, errorType, duration);
            } else {
                log.debug("layer={} operation={} event=failed errorType={} durationMs={}",
                        layer, operation, errorType, duration);
            }
            throw failure;
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
