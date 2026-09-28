package com.nicko.customer.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class DocumentProtectionTests {
    private static final String ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String HASH_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private final DocumentProtection protection = new DocumentProtection(ENCRYPTION_KEY, HASH_KEY);

    @Test
    void encryptsWithUniqueNoncesAndCanBeDecrypted() throws Exception {
        String first = protection.encrypt(" test-123 ");
        assertThat(first).isNotEqualTo(protection.encrypt("test-123")).doesNotContain("TEST-123");
        String[] pieces = first.split(":");
        assertThat(pieces[0]).isEqualTo("v1");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(ENCRYPTION_KEY), "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(pieces[1])));
        assertThat(new String(cipher.doFinal(Base64.getDecoder().decode(pieces[2])), StandardCharsets.UTF_8))
                .isEqualTo("TEST-123");
    }

    @Test
    void hashingIsCanonicalAndScopedByCountryAndDocumentType() {
        String hash = protection.hash("PASSPORT", "KE", " test-123 ");
        assertThat(hash).isEqualTo(protection.hash("PASSPORT", "KE", "TEST-123"))
                .isNotEqualTo(protection.hash("NATIONAL_ID", "KE", "TEST-123"))
                .isNotEqualTo(protection.hash("PASSPORT", "UG", "TEST-123"));
    }

    @Test
    void missingOrInvalidKeysFailClosed() {
        for (String key : new String[]{"", "invalid base64", "YQ=="}) {
            var missing = new DocumentProtection(key, key);
            assertThatThrownBy(() -> missing.encrypt("test")).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThatThrownBy(() -> missing.hash("PASSPORT", "KE", "test")).isInstanceOf(ResponseStatusException.class);
        }
    }
}
