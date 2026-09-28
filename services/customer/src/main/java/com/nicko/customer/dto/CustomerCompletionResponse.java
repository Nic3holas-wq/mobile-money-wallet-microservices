package com.nicko.customer.dto;
import java.util.List;
public record CustomerCompletionResponse(boolean complete, List<String> outstandingSteps) {}
