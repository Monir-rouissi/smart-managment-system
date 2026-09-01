package com.smartmgmt.management.customer.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartmgmt.management.customer.Customer;

public record CustomerResponse(
        UUID id,
        String name,
        String email,
        String phone,
        String company,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                c.getId(), c.getName(), c.getEmail(), c.getPhone(),
                c.getCompany(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
