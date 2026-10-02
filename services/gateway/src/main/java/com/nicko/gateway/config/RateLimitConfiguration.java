package com.nicko.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfiguration {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange ->
                exchange.getPrincipal()
                        .map(principal -> "user:" + principal.getName())
                        .switchIfEmpty(
                                Mono.just(
                                        "ip:" + resolveClientIp(exchange)
                                )
                        );
    }

    private String resolveClientIp(
            org.springframework.web.server.ServerWebExchange exchange) {

        String forwardedFor =
                exchange.getRequest()
                        .getHeaders()
                        .getFirst("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        var remoteAddress =
                exchange.getRequest().getRemoteAddress();

        if (remoteAddress != null &&
                remoteAddress.getAddress() != null) {

            return remoteAddress
                    .getAddress()
                    .getHostAddress();
        }

        return "unknown";
    }
}
