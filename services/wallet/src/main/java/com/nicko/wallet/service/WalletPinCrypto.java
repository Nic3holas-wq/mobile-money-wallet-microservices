package com.nicko.wallet.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class WalletPinCrypto {

    private static final int PIN_HASH_ITERATIONS = 310_000;
    private static final int PIN_HASH_BITS = 256;

    private final byte[] hmacKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public WalletPinCrypto(@Value("${app.stepup.hmac-key:}") String configuredKey) {
        this.hmacKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return hmacKey.length >= 32;
    }

    public String newSalt() {
        requireConfigured();
        byte[] salt = new byte[24];
        secureRandom.nextBytes(salt);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(salt);
    }

    public String newStepupToken() {
        byte[] token = new byte[32];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public String hashPin(UUID customerId, String pin, String salt) {
        String pepperedPin = hmac("wallet-pin:" + customerId + ":" + pin);
        PBEKeySpec spec = new PBEKeySpec(pepperedPin.toCharArray(),
                Base64.getUrlDecoder().decode(salt), PIN_HASH_ITERATIONS, PIN_HASH_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(derived);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive wallet PIN hash", exception);
        } finally {
            spec.clearPassword();
        }
    }

    public String hashStepupToken(String token) {
        return hmac("wallet-stepup-token:" + token);
    }

    public boolean matches(String candidate, String expectedHash) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private String hmac(String value) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash wallet PIN material", exception);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("WALLET_STEPUP_HMAC_KEY must contain at least 32 bytes");
        }
    }
}
