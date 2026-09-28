package com.nicko.customer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDate;

@Component
public class OnboardingPolicy {
    private final Clock clock;
    private final int minimumAge;
    public OnboardingPolicy(Clock clock, @Value("${app.onboarding.minimum-age:18}") int minimumAge) {
        if (minimumAge < 0 || minimumAge > 120) { throw new IllegalArgumentException("Minimum age must be between 0 and 120"); }
        this.clock = clock;
        this.minimumAge = minimumAge;
    }
    public boolean oldEnough(LocalDate dateOfBirth) {
        return dateOfBirth != null && !dateOfBirth.isAfter(LocalDate.now(clock).minusYears(minimumAge));
    }
    public void validate(LocalDate dateOfBirth) {
        if (!oldEnough(dateOfBirth)) {
            throw new FieldValidationException("dateOfBirth", "must meet the minimum age of " + minimumAge);
        }
    }
}
