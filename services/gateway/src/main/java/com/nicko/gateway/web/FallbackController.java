package com.nicko.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/customer-service")
    public Mono<ResponseEntity<Map<String, Object>>> customerServiceFallback() {
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", 503,
                        "error", "Service Unavailable",
                        "detail", "Customer service is temporarily unavailable. Please try again shortly.")));
    }

    @RequestMapping("/fallback/payment-service")
    public Mono<ResponseEntity<Map<String, Object>>> paymentServiceFallback() {
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", 503,
                        "error", "Service Unavailable",
                        "detail", "Payment service is temporarily unavailable. Please try again shortly.")));
    }
}
