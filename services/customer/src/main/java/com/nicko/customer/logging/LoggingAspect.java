package com.nicko.customer.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Slf4j
@Order(0)
public class LoggingAspect {
    @Around("execution(public * com.nicko.customer.controller..*(..))")
    public Object logController(ProceedingJoinPoint invocation) throws Throwable {
        return logOperation(invocation, true);
    }

    @Around("execution(public * com.nicko.customer.service..*(..))")
    public Object logService(ProceedingJoinPoint invocation) throws Throwable {
        return logOperation(invocation, false);
    }

    private Object logOperation(ProceedingJoinPoint invocation, boolean controller) throws Throwable {
        String operation = invocation.getSignature().toShortString();
        long started = System.nanoTime();
        log.debug("operation={} event=started", operation);
        try {
            Object result = invocation.proceed();
            long duration = elapsedMillis(started);
            if (controller) {
                int status = result instanceof ResponseEntity<?> response ? response.getStatusCode().value() : 200;
                log.info("operation={} event=completed status={} durationMs={}", operation, status, duration);
            } else {
                log.debug("operation={} event=completed durationMs={}", operation, duration);
            }
            return result;
        } catch (Throwable failure) {
            // The exception handler determines the HTTP response; do not guess its status here.
            String errorType = failure.getClass().getSimpleName();
            long duration = elapsedMillis(started);
            if (!controller) {
                log.debug("operation={} event=failed errorType={} durationMs={}", operation, errorType, duration);
            } else if (failure instanceof ResponseStatusException status && status.getStatusCode().is4xxClientError()) {
                log.warn("operation={} event=rejected status={} errorType={} durationMs={}",
                        operation, status.getStatusCode().value(), errorType, duration);
            } else {
                log.error("operation={} event=failed errorType={} durationMs={}", operation, errorType, duration);
            }
            throw failure;
        }
    }

    @Before("execution(* com.nicko.customer.config.SecurityErrorHandler.commence(..))")
    public void logUnauthenticated() {
        log.warn("event=authentication_rejected status=401");
    }

    @Before("execution(* com.nicko.customer.config.SecurityErrorHandler.handle(..))")
    public void logForbidden() {
        log.warn("event=access_denied status=403");
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
