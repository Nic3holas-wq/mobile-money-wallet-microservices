package com.nicko.payment.wallet;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ServiceAccountTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn
) {
}
