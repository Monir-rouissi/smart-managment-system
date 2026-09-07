package com.smartmgmt.auth.dto;

import java.util.UUID;

import com.smartmgmt.management.user.User;

public record MeResponse(UUID id, String email, String fullName, String role) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name());
    }
}
