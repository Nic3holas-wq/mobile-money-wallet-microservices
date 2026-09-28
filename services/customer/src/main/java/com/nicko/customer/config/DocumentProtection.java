package com.nicko.customer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Component
public class DocumentProtection {
    private final String encryptionKey;
    private final String hashKey;
    private final SecureRandom random = new SecureRandom();

    public DocumentProtection(@Value("${app.kyc.document-encryption-key:}") String encryptionKey,
            @Value("${app.kyc.document-hash-key:}") String hashKey) {
        this.encryptionKey = encryptionKey;
        this.hashKey = hashKey;
    }

    public String normalize(String number) {
        return number.strip().toUpperCase(Locale.ROOT);
    }

    public String hash(String type, String country, String number) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(hashKey), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    (type + ":" + country + ":" + normalize(number)).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Document hashing unavailable"); }
    }

    public String encrypt(String number) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(encryptionKey), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(normalize(number).getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Document encryption unavailable"); }
    }

    private byte[] key(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) { return decoded; }
        } catch (IllegalArgumentException ignored) { /* Report configuration failure without exposing the value. */ }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document protection keys are not configured");
    }
}
