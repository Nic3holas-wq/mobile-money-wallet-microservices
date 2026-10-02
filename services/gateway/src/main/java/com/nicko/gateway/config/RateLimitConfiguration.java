package com.nicko.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimitConfiguration {

    @Bean
    public KeyResolver userKeyResolver(
            @Value("${app.rate-limit.trusted-proxies:0}") int trustedProxies) {

        XForwardedRemoteAddressResolver proxyResolver = trustedProxies > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxies)
                : null;

        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(
                        () -> "ip:" + resolveClientIp(exchange, proxyResolver)));
    }

    private String resolveClientIp(ServerWebExchange exchange,
                                   XForwardedRemoteAddressResolver proxyResolver) {
        InetSocketAddress address = proxyResolver != null
                ? proxyResolver.resolve(exchange)
                : exchange.getRequest().getRemoteAddress();

        return (address != null && address.getAddress() != null)
                ? address.getAddress().getHostAddress()
                : "unknown";
    }
}