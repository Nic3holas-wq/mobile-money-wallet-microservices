package com.nicko.wallet.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class WalletPinCryptoTests {

    @Test
    void reportsKeyConfiguredOnlyAtThirtyTwoBytes() {
        assertThat(new WalletPinCrypto("short").isConfigured()).isFalse();
        assertThat(new WalletPinCrypto("k".repeat(32)).isConfigured()).isTrue();
    }

    @Test
    void createsSaltedPinHashAndComparesInConstantTimeHelper() {
        WalletPinCrypto crypto = new WalletPinCrypto("k".repeat(32));
        UUID customerId = UUID.randomUUID();
        String salt = crypto.newSalt();
        String hash = crypto.hashPin(customerId, "123456", salt);

        assertThat(salt).isNotBlank();
        assertThat(hash).hasSize(64).doesNotContain("123456");
        assertThat(crypto.matches(hash, hash)).isTrue();
        assertThat(crypto.matches("0".repeat(64), hash)).isFalse();
        assertThat(crypto.hashPin(customerId, "123456", crypto.newSalt())).isNotEqualTo(hash);
        assertThat(crypto.hashPin(UUID.randomUUID(), "123456", salt)).isNotEqualTo(hash);
    }

    @Test
    void hashesStepupTokensWithoutStoringTheRawValue() {
        WalletPinCrypto crypto = new WalletPinCrypto("k".repeat(32));
        String rawToken = crypto.newStepupToken();
        String hash = crypto.hashStepupToken(rawToken);

        assertThat(rawToken).isNotBlank();
        assertThat(hash).hasSize(64).isNotEqualTo(rawToken);
        assertThat(crypto.hashStepupToken(rawToken)).isEqualTo(hash);
    }

    @Test
    void rejectsCryptoOperationsWithoutSufficientKeyMaterial() {
        WalletPinCrypto crypto = new WalletPinCrypto("short");

        assertThatIllegalStateException().isThrownBy(crypto::newSalt)
                .withMessageContaining("at least 32 bytes");
        assertThatIllegalStateException().isThrownBy(() -> crypto.hashStepupToken("token"))
                .withMessageContaining("at least 32 bytes");
    }
}
