package com.nicko.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LimitRemovalRequest(@NotBlank @Size(max = 255) String reason) {}
