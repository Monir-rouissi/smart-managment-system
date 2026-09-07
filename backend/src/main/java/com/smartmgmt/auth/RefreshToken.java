package com.smartmgmt.auth;

import java.time.Instant;

import com.smartmgmt.common.BaseEntity;
import com.smartmgmt.management.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A hashed, revocable refresh token. The raw value is only ever returned to
 * the client once, at issue time; only its HMAC
 * ({@link JwtService#hashRefreshToken}) is stored here. Refresh rotates the
 * token: the old row is marked revoked and a new one is inserted.
 */
@Getter
@Setter
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;
}
