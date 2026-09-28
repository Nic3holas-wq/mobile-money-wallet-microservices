package com.nicko.customer.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Only confirmation failures use this exception: their attempt counters must commit.
public class VerificationRejectedException extends ResponseStatusException {
    public VerificationRejectedException() { super(HttpStatus.BAD_REQUEST, "Verification code is invalid, expired or already used"); }
}
