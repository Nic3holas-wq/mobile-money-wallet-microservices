package com.nicko.customer.customer;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerJwtSecurityTests {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final String ISSUER = "https://issuer.example.test/realms/customer";
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @Autowired MockMvc mvc;
    @AfterAll static void stopServer() { JWKS.stop(0); }

    private String token(String audience, String issuer, Instant expiration, RSAKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiration));
        if (audience != null) { claims.audience(audience); }
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    @Test
    void acceptsSignedTokenForThisAudience() throws Exception {
        mvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " +
                        token("customer-service", ISSUER, Instant.now().plusSeconds(300), KEY)))
                .andExpect(status().isNotFound()) // Authenticated, but this new UUID has no customer.
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsWrongAndMissingAudienceWrongIssuerExpiredAndInvalidSignature() throws Exception {
        String[] tokens = {
                token("other-service", ISSUER, Instant.now().plusSeconds(300), KEY),
                token(null, ISSUER, Instant.now().plusSeconds(300), KEY),
                token("customer-service", "https://wrong-issuer.test", Instant.now().plusSeconds(300), KEY),
                token("customer-service", ISSUER, Instant.now().minusSeconds(300), KEY),
                token("customer-service", ISSUER, Instant.now().plusSeconds(300), new RSAKeyGenerator(2048).generate())
        };
        for (String token : tokens) {
            mvc.perform(get("/api/v1/customers/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.instance").value("/api/v1/customers/me"));
        }
    }
}
