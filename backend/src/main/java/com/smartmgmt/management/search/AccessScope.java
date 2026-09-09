package com.smartmgmt.management.search;

import java.util.UUID;

import com.smartmgmt.auth.SecurityUtils;
import com.smartmgmt.auth.UserPrincipal;
import com.smartmgmt.management.user.Role;

/**
 * The caller's visibility, resolved once and pushed into the SQL predicate.
 *
 * <p>Search is the first endpoint that returns rows the caller did not name by
 * id, so the ownership rule cannot be applied after the fact: taking the top 50
 * by rank and then dropping what the caller may not see returns short pages and
 * a total that lies. It has to be part of the WHERE clause.
 */
public record AccessScope(boolean privileged, UUID userId) {

    public static AccessScope current() {
        UserPrincipal principal = SecurityUtils.currentUser();
        return new AccessScope(principal.getRole() != Role.USER, principal.getId());
    }
}
