package com.nicko.payment.provider.mpesa;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MpesaTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn
) {
}
