package com.smartmgmt.common;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope returned by list endpoints, so the JSON shape does
 * not depend on Spring Data's internal {@code PageImpl} serialization.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
