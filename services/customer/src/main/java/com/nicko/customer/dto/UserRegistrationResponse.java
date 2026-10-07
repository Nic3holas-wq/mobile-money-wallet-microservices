package com.nicko.customer.dto;

import java.util.UUID;

public record UserRegistrationResponse(UUID userId, String username, String email, String status) {
}
