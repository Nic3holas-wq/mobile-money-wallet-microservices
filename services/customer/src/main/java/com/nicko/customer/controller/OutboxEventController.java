package com.nicko.customer.controller;

import com.nicko.customer.dto.*;
import com.nicko.customer.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.nicko.customer.config.AuthenticatedCustomer.userId;

@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}/outbox-events")
@RequiredArgsConstructor
public class OutboxEventController {
    private final OutboxEventService service;
    @GetMapping
    public PageResponse<OutboxEventResponse> list(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(customerId, page, size);
    }
    @GetMapping("/{id}")
    public OutboxEventResponse get(@PathVariable UUID customerId, @PathVariable UUID id) {
        return service.get(customerId, id);
    }
}
