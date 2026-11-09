package dev.codearena.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.codearena.common.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Access tokens: short-lived RS256 JWTs, verified by every service with the public key (JWKS).
 * Refresh tokens: opaque random strings; only their SHA-256 hash is stored (Redis, with TTL).
 * Rotation: every refresh issues a new refresh token; presenting an already-used one revokes the whole family.
 */
@Service
class TokenService {
    private final RSAKey key;
    private final JwtProps props;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    TokenService(RSAKey key, JwtProps props, StringRedisTemplate redis) {
        this.key = key;
        this.props = props;
        this.redis = redis;
    }

    record Tokens(String accessToken, String refreshToken, long expiresIn) {}

    record Rotation(String userId, String refreshToken) {}

    Tokens issue(UserRepository.User u, String familyId) {
        Duration ttl = u.isAdmin() ? props.adminAccessTtl() : props.accessTtl();
        return new Tokens(accessToken(u, ttl), newRefreshToken(u.id(), familyId), ttl.toSeconds());
    }

    String accessToken(UserRepository.User u, Duration ttl) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .audience(props.audience())
                .subject(u.id())
                .claim("roles", List.copyOf(u.roles()))
                .claim("name", u.displayName())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign token", e);
        }
    }

    String newRefreshToken(String userId, String familyId) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        redis.opsForValue().set("rt:" + sha256(token), userId + "|" + familyId, props.refreshTtl());
        return token;
    }

    Rotation rotate(String presented) {
        if (presented == null || presented.isBlank()) throw invalid();
        String hash = sha256(presented);
        String value = redis.opsForValue().getAndDelete("rt:" + hash);
        if (value == null) {
            // Unknown or already used. If it was used before, someone replayed it: revoke the family.
            String family = redis.opsForValue().get("rt-used:" + hash);
            if (family != null) redis.opsForValue().set("rt-revoked:" + family, "1", props.refreshTtl());
            throw invalid();
        }
        String[] parts = value.split("\\|");
        if (Boolean.TRUE.equals(redis.hasKey("rt-revoked:" + parts[1]))) throw invalid();
        redis.opsForValue().set("rt-used:" + hash, parts[1], props.refreshTtl());
        return new Rotation(parts[0], newRefreshToken(parts[0], parts[1]));
    }

    void revokeRefresh(String presented) {
        if (presented != null && !presented.isBlank()) redis.delete("rt:" + sha256(presented));
    }

    /** The gateway rejects access tokens whose jti is on this list (logout before expiry). */
    void denyAccessToken(String jti, Instant expiresAt) {
        Duration left = Duration.between(Instant.now(), expiresAt);
        if (!left.isNegative() && !left.isZero()) redis.opsForValue().set("deny:" + jti, "1", left);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "please log in again");
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}