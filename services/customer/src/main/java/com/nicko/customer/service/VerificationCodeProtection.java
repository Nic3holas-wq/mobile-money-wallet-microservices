package com.nicko.customer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class VerificationCodeProtection {
    private final String configuredKey;
    public VerificationCodeProtection(@Value("${app.verification.hmac-key:}") String key) { configuredKey = key; }
    public String hash(String value) {
        try {
            byte[] key = Base64.getDecoder().decode(configuredKey);
            if (key.length != 32) { throw new IllegalArgumentException(); }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Contact verification key is not configured");
        }
    }
}
