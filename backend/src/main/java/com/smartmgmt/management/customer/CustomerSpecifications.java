package com.smartmgmt.management.customer;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    /** Case-insensitive match against name, company or email. */
    static Specification<Customer> matches(String q) {
        if (!StringUtils.hasText(q)) {
            return null;
        }
        String like = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), like),
                cb.like(cb.lower(root.get("company")), like),
                cb.like(cb.lower(root.get("email")), like));
    }
}
