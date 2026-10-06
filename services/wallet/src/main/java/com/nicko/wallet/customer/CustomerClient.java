package com.nicko.wallet.customer;

import com.nicko.wallet.config.CustomerFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(
        name = "customer-service",
        url = "${clients.customer.url}",
        configuration = CustomerFeignConfig.class
)
public interface CustomerClient {

    @GetMapping("/internal/customers/{id}")
    CustomerDto getCustomer(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/customers/me")
    CustomerDto getCurrentCustomer();
}
