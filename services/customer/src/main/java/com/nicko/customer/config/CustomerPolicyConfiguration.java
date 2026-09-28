package com.nicko.customer.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerPolicyConfiguration {
    @Bean public Clock customerClock() { return Clock.systemUTC(); }
}
