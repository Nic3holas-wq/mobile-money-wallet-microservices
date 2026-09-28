package com.nicko.customer.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ApiPages {
    private ApiPages() {}
    public static PageRequest of(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be nonnegative and size between 1 and 100");
        }
        return PageRequest.of(page, size, Sort.by("createdAt", "id"));
    }
}
