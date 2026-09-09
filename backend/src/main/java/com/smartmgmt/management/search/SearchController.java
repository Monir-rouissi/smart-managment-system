package com.smartmgmt.management.search;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.smartmgmt.common.PageResponse;
import com.smartmgmt.management.search.dto.SearchHit;

/**
 * {@code GET /api/search}.
 *
 * <p>{@code isAuthenticated()} is the whole authorisation annotation: the row-level
 * rule cannot be expressed as a role check, so it lives in the SQL predicate
 * ({@link AccessScope}) instead. A {@code projectId} the caller cannot see simply
 * returns nothing rather than 403 -- a search endpoint that distinguishes "no
 * results" from "not allowed" is an existence oracle for other people's projects.
 */
@RestController
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/api/search")
    public PageResponse<SearchHit> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "HYBRID") SearchMode mode,
            @RequestParam(required = false) UUID projectId,
            @PageableDefault(size = 20) Pageable pageable) {
        // No sort allowlist here because there is no sort at all: results are ranked
        // by relevance, and an ORDER BY chosen by the client would discard that.
        if (pageable.getSort().isSorted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search results are ranked by relevance; ?sort= is not supported");
        }
        return PageResponse.from(service.search(q, mode, projectId, pageable));
    }
}
