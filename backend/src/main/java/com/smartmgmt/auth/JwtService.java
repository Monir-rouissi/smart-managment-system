package com.smartmgmt.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.smartmgmt.management.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies short-lived HS256 access tokens, and generates/hashes
 * opaque refresh token values. The raw refresh token is only ever seen by
 * the client; only its HMAC is persisted (see {@link RefreshToken}).
 */
@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final SecretKeySpec refreshHashKey;
    private final SecureRandom random = new SecureRandom();

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.refreshHashKey = deriveRefreshHashKey(properties.getSecret());
    }

    // Derives a distinct key for refresh-token hashing (domain-separated from the
    // JWT signing key) so the same secret isn't reused for two purposes as-is.
    private static SecretKeySpec deriveRefreshHashKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(("refresh-token-hmac:" + secret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())))
                .signWith(signingKey)
                .compact();
    }

    /**
     * @throws JwtException if the token is malformed, expired, has a bad
     *      signature, or was issued by a different issuer.
     */
    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Duration accessTokenTtl() {
        return properties.getAccessTokenTtl();
    }

    public Duration refreshTokenTtl() {
        return properties.getRefreshTokenTtl();
    }

    /** A random opaque value handed to the client; never stored as-is. */
    public String generateRefreshTokenValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** HMAC of a raw refresh token value, used both to store and to look one up. */
    public String hashRefreshToken(String rawToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(refreshHashKey);
            byte[] out = mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash refresh token", e);
        }
    }
}
