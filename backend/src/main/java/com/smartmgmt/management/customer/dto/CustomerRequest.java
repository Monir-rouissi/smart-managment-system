package com.smartmgmt.management.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank @Size(max = 200) String name,
        @Email @Size(max = 320) String email,
        @Size(max = 50) String phone,
        @Size(max = 200) String company,
        String notes) {
}
