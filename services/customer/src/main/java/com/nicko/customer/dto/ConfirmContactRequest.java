package com.nicko.customer.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record ConfirmContactRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
