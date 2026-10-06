package com.nicko.wallet.controller;

import com.nicko.wallet.dto.SetWalletPinRequest;
import com.nicko.wallet.service.WalletPinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets/{walletId}/pin")
@RequiredArgsConstructor
public class WalletPinController {

    private final WalletPinService walletPinService;

    @PutMapping
    public ResponseEntity<Void> setOrChangePin(@PathVariable UUID walletId,
                                               @Valid @RequestBody SetWalletPinRequest request) {
        walletPinService.setOrChange(walletId, request.newPin(), request.currentPin());
        return ResponseEntity.noContent().build();
    }
}
