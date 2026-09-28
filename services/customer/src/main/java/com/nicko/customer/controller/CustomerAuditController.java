package com.nicko.customer.controller;

import com.nicko.customer.dto.*;
import com.nicko.customer.service.CustomerAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}/audit-records")
@RequiredArgsConstructor
public class CustomerAuditController {
    private final CustomerAuditService service;
    @GetMapping
    public PageResponse<CustomerAuditResponse> list(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.history(customerId, page, size);
    }
}
