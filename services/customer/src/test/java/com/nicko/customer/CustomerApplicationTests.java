package com.nicko.customer;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import static org.junit.jupiter.api.Assertions.*;

class CustomerApplicationTests {
    @Test void applicationEnablesScheduledOutboxPublishing() {
        assertTrue(CustomerApplication.class.isAnnotationPresent(EnableScheduling.class));
    }
}
