package com.nicko.wallet.controller;

import com.nicko.wallet.dto.ReverseWalletTransferRequest;
import com.nicko.wallet.dto.WalletTransferResponse;
import com.nicko.wallet.service.WalletTransferReversalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/transfers")
@RequiredArgsConstructor
public class AdminWalletTransferController {
    private final WalletTransferReversalService reversalService;

    @PostMapping("/{transferId}/reversal")
    @PreAuthorize("hasAuthority('WALLET_ADMIN')")
    public WalletTransferResponse reverse(@PathVariable UUID transferId,
                                          @AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody ReverseWalletTransferRequest request) {
        return reversalService.reverse(transferId, jwt.getSubject(), request.reason());
    }
}
