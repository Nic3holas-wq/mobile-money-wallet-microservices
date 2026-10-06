package com.nicko.wallet.customer;

import java.util.UUID;

public record CustomerDto(UUID id, String firstName, String lastName, String customerStatus) {}
