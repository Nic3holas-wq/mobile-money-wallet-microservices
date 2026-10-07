package com.nicko.customer.controller;

import com.nicko.customer.dto.UserRegistrationRequest;
import com.nicko.customer.dto.UserRegistrationResponse;
import com.nicko.customer.service.KeycloakUserRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class UserRegistrationController {
    private final KeycloakUserRegistrationService registrationService;

    @PostMapping("/register")
    public ResponseEntity<UserRegistrationResponse> register(
            @Valid @RequestBody UserRegistrationRequest request) {
        return ResponseEntity.status(201).body(registrationService.register(request));
    }
}
