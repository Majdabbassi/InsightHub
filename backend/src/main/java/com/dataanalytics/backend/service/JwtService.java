package com.dataanalytics.backend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    /**
     * Placeholder secrets that must never sign real tokens. Starting up with
     * one of these outside a dev/test profile would make every issued token
     * forgeable, so the application refuses to start instead.
     */
    private static final Set<String> INSECURE_SECRETS = Set.of(
            "dev-only-jwt-secret-key-change-me-in-production-min-32-bytes",
            "replace-with-a-long-random-secret-at-least-32-bytes",
            "change_me",
            "");

    private static final int MIN_SECRET_BYTES = 32; // HS256 needs >= 256 bits

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiration-ms}") long expirationMs,
                      Environment environment) {
        boolean devLike = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
        if (!devLike && INSECURE_SECRETS.contains(secret)) {
            throw new IllegalStateException(
                    "Refusing to start: jwt.secret is a known placeholder. "
                            + "Generate one with `openssl rand -base64 64` and set JWT_SECRET.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "Refusing to start: jwt.secret must be at least "
                            + MIN_SECRET_BYTES + " bytes (got " + keyBytes.length + ").");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserDetails userDetails) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("role", extractRole(userDetails))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String extractRole(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElse("ROLE_USER");
    }
}
