package com.nicko.payment.controller;

import com.nicko.payment.dto.DepositRequest;
import com.nicko.payment.dto.MpesaCallbackResult;
import com.nicko.payment.dto.PaymentResponse;
import com.nicko.payment.service.MpesaCallbackService;
import com.nicko.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {

    private final PaymentService paymentService;
    private final MpesaCallbackService callbackService;

    @PostMapping("/deposits")
    public ResponseEntity<PaymentResponse> initiateDeposit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        PaymentResponse response = paymentService.initiateDeposit(customerId(jwt), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        return paymentService.getPayment(customerId(jwt), paymentId);
    }

    @PostMapping("/callbacks/provider")
    public ResponseEntity<Map<String, Object>> receiveMpesaCallback(
            @RequestParam String token, @RequestBody Map<String, Object> payload) {
        MpesaCallbackResult result = callbackService.receive(token, payload);
        Map<String, Object> acknowledgement = Map.of("ResultCode", 0, "ResultDesc", result.status());
        if ("FAILED".equals(result.status())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(acknowledgement);
        }
        return ResponseEntity.ok(acknowledgement);
    }

    private static UUID customerId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated subject is not a customer ID");
        }
    }
}
