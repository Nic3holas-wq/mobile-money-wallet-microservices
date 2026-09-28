package com.nicko.customer.service;

import com.nicko.customer.customer.enums.ContactType;
import java.util.UUID;

public interface VerificationDelivery {
    void send(UUID challengeId, ContactType type, String destination, String code);
}
