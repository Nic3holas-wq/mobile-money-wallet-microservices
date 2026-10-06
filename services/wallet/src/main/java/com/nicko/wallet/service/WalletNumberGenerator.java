package com.nicko.wallet.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class WalletNumberGenerator {

    private static final String PREFIX = "129";

    private final SecureRandom random = new SecureRandom();

    public String generate() {

        long number = 100_000_000L
                + random.nextLong(900_000_000L);

        return PREFIX + number;
    }
}