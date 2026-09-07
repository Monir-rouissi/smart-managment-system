package com.smartmgmt.common;

import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Restricts {@code ?sort=} to an explicit allowlist per resource so a client
 * cannot sort by (and thereby probe) a field outside the response DTO — e.g.
 * a related entity's column such as {@code owner.passwordHash}.
 */
public final class SortSupport {

    private SortSupport() {
    }

    public static void validate(Sort sort, Set<String> allowedProperties) {
        for (Sort.Order order : sort) {
            if (!allowedProperties.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '%s'. Allowed fields: %s".formatted(order.getProperty(), allowedProperties));
            }
        }
    }
}
