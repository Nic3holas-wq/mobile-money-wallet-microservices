package com.nicko.wallet.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalletNumberGeneratorTests {

    private final WalletNumberGenerator generator = new WalletNumberGenerator();

    @Test
    void generatedNumbersHaveExpectedPrefixAndLength() {
        assertThat(generator.generate()).matches("129\\d{9}");
    }
}
