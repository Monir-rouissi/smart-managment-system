package com.smartmgmt.management.project;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import jakarta.persistence.criteria.Predicate;

final class ProjectSpecifications {

    private ProjectSpecifications() {
    }

    static Specification<Project> build(String q, ProjectStatus status, UUID customerId,
            UUID ownerId, Boolean overdue) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(q)) {
                String like = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("description")), like)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customer").get("id"), customerId));
            }
            if (ownerId != null) {
                predicates.add(cb.equal(root.get("owner").get("id"), ownerId));
            }
            if (Boolean.TRUE.equals(overdue)) {
                predicates.add(cb.and(
                        cb.isNotNull(root.get("dueDate")),
                        cb.lessThan(root.get("dueDate"), LocalDate.now()),
                        root.get("status").in(ProjectStatus.COMPLETED, ProjectStatus.CANCELLED).not()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
