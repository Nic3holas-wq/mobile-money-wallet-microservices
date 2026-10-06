package com.nicko.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record StepupTokenResponse(UUID transferId, String stepupToken, Instant expiresAt) {
}
