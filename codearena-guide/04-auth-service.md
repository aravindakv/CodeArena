# 04 · Auth service: JWT, refresh rotation, and two roles

auth-service is the only service that *issues* tokens. Everyone else only *verifies* them with the public key it
publishes at `/.well-known/jwks.json`, so no other service ever holds the private key.

## Design

| Token | Lifetime | Where it lives | Contents |
| --- | --- | --- | --- |
| Access token (RS256 JWT) | 15 min; 5 min for admins | Browser memory; `Authorization: Bearer` | `sub`, `roles`, `name`, `iss`, `aud`, `exp`, `jti`, `kid` |
| Refresh token (random 256-bit) | 7 days | Web: HttpOnly `ca_rt` cookie (SameSite=Strict). Mobile: response body, kept in Keystore/Keychain | nothing; the server stores only its SHA-256 hash |

- **Rotation**: every refresh returns a new refresh token and burns the old one. Presenting a used one means it was
  stolen, so the whole token family is revoked.
- **Logout** deletes the refresh token and puts the access token's `jti` on a Redis deny list until it would have
  expired; the gateway rejects deny-listed tokens.
- **Roles**: everyone registers as `USER`. `ADMIN` can only be granted by another admin, and every grant is written
  to `AuditLog`. The very first admin is whoever registers with `BOOTSTRAP_ADMIN_EMAIL` (unset it outside local dev).
- **Stable key id**: the signing key is a PEM file created once by `scripts/up.sh`; its `kid` is the key thumbprint,
  so restarts do not log everyone out.

| Capability | USER | ADMIN |
| --- | --- | --- |
| Browse problems, read statements | yes | yes |
| Write code, Run, Submit in any language | yes | yes |
| Own submissions and progress | yes | yes |
| Register for fests and appear on leaderboards | yes | yes |
| Create, edit, publish, retire problems; upload tests | — | yes |
| Start AI + RAG ingestion runs; review generated problems | — | yes |
| Create and manage fests; freeze leaderboards | — | yes |
| Platform analytics; grant or remove admin | — | yes |

## The service

**File:** `services/auth-service/build.gradle.kts`

```kotlin
plugins { id("codearena.service") }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server") // brings nimbus-jose-jwt
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-dynamodb")
    implementation("com.github.f4b6a3:ulid-creator:${Versions.ULID}")
}
```

**File:** `services/auth-service/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:9081}
spring:
  application:
    name: auth-service
  config:
    import: classpath:codearena-common.yml

codearena:
  jwt:
    issuer: codearena-auth
    audience: codearena-api
    access-ttl: 15m
    admin-access-ttl: 5m          # admins get shorter-lived tokens
    refresh-ttl: 7d
    private-key-file: ${JWT_PRIVATE_KEY_FILE:.local/jwt-private.pem}   # created by scripts/up.sh
  cookie-secure: ${COOKIE_SECURE:false}                               # true behind HTTPS
  bootstrap-admin-email: ${BOOTSTRAP_ADMIN_EMAIL:}                     # this email becomes ADMIN on register
```

**File:** `services/auth-service/src/main/java/dev/codearena/auth/AuthApplication.java`

```java
package dev.codearena.auth;

import dev.codearena.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(ApiExceptionHandler.class)
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
```

**File:** `services/auth-service/src/main/java/dev/codearena/auth/JwtProps.java`

```java
package dev.codearena.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("codearena.jwt")
public record JwtProps(String issuer, String audience, Duration accessTtl, Duration adminAccessTtl,
                       Duration refreshTtl, String privateKeyFile) {
}
```

The key is read with plain JDK classes (no BouncyCastle). `openssl genpkey` writes PKCS#8, which is what
`PKCS8EncodedKeySpec` expects.

**File:** `services/auth-service/src/main/java/dev/codearena/auth/KeyConfig.java`

```java
package dev.codearena.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Loads the RSA signing key from a PKCS#8 PEM file (scripts/up.sh creates .local/jwt-private.pem with openssl).
 * The key id (kid) is the key's thumbprint, so it stays the same across restarts and tokens remain valid.
 */
@Configuration
class KeyConfig {
    private static final Logger log = LoggerFactory.getLogger(KeyConfig.class);

    @Bean
    RSAKey signingKey(JwtProps props) throws Exception {
        for (Path p : List.of(Path.of(props.privateKeyFile()), Path.of("../..").resolve(props.privateKeyFile()))) {
            if (Files.isRegularFile(p)) {
                log.info("Loading JWT signing key from {}", p.toAbsolutePath().normalize());
                return fromPem(Files.readString(p));
            }
        }
        log.warn("No JWT key file found at {}; generating a temporary key. Tokens will not survive a restart.",
                props.privateKeyFile());
        return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint(true).generate();
    }

    static RSAKey fromPem(String pem) throws Exception {
        if (!pem.contains("BEGIN PRIVATE KEY"))
            throw new IllegalStateException("Expected a PKCS#8 key (BEGIN PRIVATE KEY). Create it with: "
                    + "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/jwt-private.pem");
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        return withThumbprintId(new RSAKey.Builder(pub).privateKey(priv));
    }

    private static RSAKey withThumbprintId(RSAKey.Builder b) throws JOSEException {
        return b.keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
    }
}
```

auth-service verifies its own tokens with the public key directly, and checks issuer and audience.

**File:** `services/auth-service/src/main/java/dev/codearena/auth/SecurityConfig.java`

```java
package dev.codearena.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import dev.codearena.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** auth-service verifies its own tokens with the public key directly (no JWKS round trip to itself). */
    @Bean
    JwtDecoder jwtDecoder(RSAKey key, JwtProps props) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(props.audience()))));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())   // stateless API; the refresh cookie is SameSite=Strict
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh",
                                "/v1/auth/logout", "/.well-known/jwks.json", "/actuator/health/**").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole(Roles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Roles.jwtConverter())))
                .build();
    }
}
```

Unique emails in DynamoDB need a trick: there is no unique index, so registration writes the user *and* an
`EMAIL#...` guard item in one transaction, each with `attribute_not_exists(PK)`. If the email exists, the whole
transaction fails.

**File:** `services/auth-service/src/main/java/dev/codearena/auth/UserRepository.java`

```java
package dev.codearena.auth;

import dev.codearena.common.web.ApiException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.codearena.common.aws.Ddb.*;

/**
 * Users table:  PK=USER#<id>     SK=PROFILE  -> the user
 *               PK=EMAIL#<email> SK=PROFILE  -> guard item that makes emails unique
 */
@Repository
class UserRepository {
    private static final String TABLE = "Users";
    private final DynamoDbClient ddb;

    UserRepository(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    record User(String id, String email, String passwordHash, String displayName, Set<String> roles,
                String status, String createdAt) {
        boolean isAdmin() { return roles.contains("ADMIN"); }
    }

    /** Writes the user and the email guard in one transaction; fails if the email is taken. */
    void create(User u) {
        Map<String, AttributeValue> user = new HashMap<>(key("USER#" + u.id(), "PROFILE"));
        user.put("userId", s(u.id()));
        user.put("email", s(u.email()));
        user.put("passwordHash", s(u.passwordHash()));
        user.put("displayName", s(u.displayName()));
        user.put("roles", AttributeValue.fromSs(List.copyOf(u.roles())));
        user.put("status", s(u.status()));
        user.put("createdAt", s(u.createdAt()));
        Map<String, AttributeValue> guard = new HashMap<>(key("EMAIL#" + u.email(), "PROFILE"));
        guard.put("userId", s(u.id()));
        try {
            ddb.transactWriteItems(t -> t.transactItems(
                    TransactWriteItem.builder().put(Put.builder().tableName(TABLE).item(user)
                            .conditionExpression("attribute_not_exists(PK)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder().tableName(TABLE).item(guard)
                            .conditionExpression("attribute_not_exists(PK)").build()).build()));
        } catch (TransactionCanceledException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "an account with this email already exists");
        }
    }

    Optional<User> byId(String id) {
        var item = ddb.getItem(r -> r.tableName(TABLE).key(key("USER#" + id, "PROFILE"))).item();
        return has(item) ? Optional.of(map(item)) : Optional.empty();
    }

    Optional<User> byEmail(String email) {
        var guard = ddb.getItem(r -> r.tableName(TABLE).key(key("EMAIL#" + email, "PROFILE"))).item();
        return has(guard) ? byId(str(guard, "userId")) : Optional.empty();
    }

    void setRoles(String id, Set<String> roles) {
        ddb.updateItem(r -> r.tableName(TABLE).key(key("USER#" + id, "PROFILE"))
                .updateExpression("SET #r = :r")
                .conditionExpression("attribute_exists(PK)")
                .expressionAttributeNames(Map.of("#r", "roles"))
                .expressionAttributeValues(Map.of(":r", AttributeValue.fromSs(List.copyOf(roles)))));
    }

    /** Admin listing. A Scan is fine for local data sizes; add a GSI before you have many users. */
    List<User> list(int limit) {
        return ddb.scan(r -> r.tableName(TABLE).limit(500)
                        .filterExpression("begins_with(PK, :p)")
                        .expressionAttributeValues(Map.of(":p", s("USER#"))))
                .items().stream().limit(limit).map(UserRepository::map).toList();
    }

    void audit(String actorId, String action, String target) {
        String now = Instant.now().toString();
        ddb.putItem(r -> r.tableName("AuditLog").item(Map.of(
                "PK", s("AUDIT#" + now.substring(0, 10)), "SK", s(now + "#" + actorId),
                "actor", s(actorId), "action", s(action), "target", s(target))));
    }

    private static User map(Map<String, AttributeValue> i) {
        return new User(str(i, "userId"), str(i, "email"), str(i, "passwordHash"), str(i, "displayName"),
                Set.copyOf(i.get("roles").ss()), str(i, "status"), str(i, "createdAt"));
    }
}
```

**File:** `services/auth-service/src/main/java/dev/codearena/auth/TokenService.java`

```java
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
```

**File:** `services/auth-service/src/main/java/dev/codearena/auth/AuthController.java`

```java
package dev.codearena.auth;

import com.github.f4b6a3.ulid.UlidCreator;
import dev.codearena.common.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Web clients get the refresh token as an HttpOnly cookie (ca_rt); mobile clients read it from the
 * JSON body and send it back in the body of /refresh. Both are accepted everywhere.
 */
@RestController
@RequestMapping("/v1/auth")
class AuthController {
    static final String COOKIE = "ca_rt";

    private final UserRepository users;
    private final TokenService tokens;
    private final PasswordEncoder encoder;
    private final JwtProps props;
    private final boolean cookieSecure;
    private final String bootstrapAdminEmail;

    AuthController(UserRepository users, TokenService tokens, PasswordEncoder encoder, JwtProps props,
                   @Value("${codearena.cookie-secure}") boolean cookieSecure,
                   @Value("${codearena.bootstrap-admin-email:}") String bootstrapAdminEmail) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.props = props;
        this.cookieSecure = cookieSecure;
        this.bootstrapAdminEmail = bootstrapAdminEmail == null ? "" : bootstrapAdminEmail.trim().toLowerCase();
    }

    record RegisterRequest(@Email @NotBlank String email,
                           @Size(min = 8, max = 128) String password,
                           @Pattern(regexp = "[A-Za-z0-9_.-]{3,24}", message = "3-24 letters, digits, _ . -")
                           String displayName) {}

    record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    record RefreshRequest(String refreshToken) {}

    record MeResponse(String id, String email, String displayName, Set<String> roles, String createdAt) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    MeResponse register(@Valid @RequestBody RegisterRequest r) {
        String email = r.email().trim().toLowerCase();
        Set<String> roles = new HashSet<>(Set.of("USER"));
        if (!bootstrapAdminEmail.isEmpty() && bootstrapAdminEmail.equals(email)) roles.add("ADMIN");
        var u = new UserRepository.User(UlidCreator.getMonotonicUlid().toString(), email,
                encoder.encode(r.password()), r.displayName(), roles, "ACTIVE", Instant.now().toString());
        users.create(u);
        return me(u);
    }

    @PostMapping("/login")
    TokenService.Tokens login(@Valid @RequestBody LoginRequest r, HttpServletResponse res) {
        var u = users.byEmail(r.email().trim().toLowerCase())
                .filter(x -> encoder.matches(r.password(), x.passwordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "wrong email or password"));
        if (!"ACTIVE".equals(u.status())) throw ApiException.forbidden("ACCOUNT_SUSPENDED", "account suspended");
        var t = tokens.issue(u, UUID.randomUUID().toString());
        setCookie(res, t.refreshToken(), props.refreshTtl());
        return t;
    }

    @PostMapping("/refresh")
    TokenService.Tokens refresh(@CookieValue(name = COOKIE, required = false) String cookie,
                                @RequestBody(required = false) RefreshRequest body, HttpServletResponse res) {
        String presented = cookie != null ? cookie : body == null ? null : body.refreshToken();
        var rot = tokens.rotate(presented);
        var u = users.byId(rot.userId()).orElseThrow(() -> ApiException.notFound("user"));
        Duration ttl = u.isAdmin() ? props.adminAccessTtl() : props.accessTtl();
        setCookie(res, rot.refreshToken(), props.refreshTtl());
        return new TokenService.Tokens(tokens.accessToken(u, ttl), rot.refreshToken(), ttl.toSeconds());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = COOKIE, required = false) String cookie,
                @RequestBody(required = false) RefreshRequest body,
                @AuthenticationPrincipal Jwt jwt, HttpServletResponse res) {
        tokens.revokeRefresh(cookie != null ? cookie : body == null ? null : body.refreshToken());
        if (jwt != null && jwt.getId() != null) tokens.denyAccessToken(jwt.getId(), jwt.getExpiresAt());
        setCookie(res, "", Duration.ZERO);
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return me(users.byId(jwt.getSubject()).orElseThrow(() -> ApiException.notFound("user")));
    }

    private static MeResponse me(UserRepository.User u) {
        return new MeResponse(u.id(), u.email(), u.displayName(), u.roles(), u.createdAt());
    }

    private void setCookie(HttpServletResponse res, String value, Duration maxAge) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(cookieSecure).sameSite("Strict")
                .path("/api/v1/auth").maxAge(maxAge).build().toString());
    }

}
```

**File:** `services/auth-service/src/main/java/dev/codearena/auth/AdminUserController.java`

```java
package dev.codearena.auth;

import dev.codearena.common.security.Roles;
import dev.codearena.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only an ADMIN can grant or remove ADMIN. Every change is written to the AuditLog table. */
@RestController
@RequestMapping("/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {
    private final UserRepository users;

    AdminUserController(UserRepository users) {
        this.users = users;
    }

    record UserRow(String id, String email, String displayName, Set<String> roles, String status) {}

    record RoleChange(boolean admin) {}

    @GetMapping
    List<UserRow> list() {
        return users.list(200).stream()
                .map(u -> new UserRow(u.id(), u.email(), u.displayName(), u.roles(), u.status())).toList();
    }

    @PostMapping("/{id}/admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setAdmin(@PathVariable String id, @RequestBody RoleChange change, @AuthenticationPrincipal Jwt actor) {
        if (id.equals(actor.getSubject()) && !change.admin())
            throw ApiException.badRequest("SELF_DEMOTION", "you cannot remove your own admin role");
        var u = users.byId(id).orElseThrow(() -> ApiException.notFound("user"));
        Set<String> roles = new HashSet<>(u.roles());
        if (change.admin()) roles.add(Roles.ADMIN); else roles.remove(Roles.ADMIN);
        users.setRoles(id, roles);
        users.audit(actor.getSubject(), change.admin() ? "GRANT_ADMIN" : "REVOKE_ADMIN", id);
    }
}
```

**File:** `services/auth-service/src/main/java/dev/codearena/auth/JwksController.java`

```java
package dev.codearena.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public keys only. Every other service (and the gateway) verifies tokens with these. */
@RestController
class JwksController {
    private final RSAKey key;

    JwksController(RSAKey key) {
        this.key = key;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
```

**File:** `services/auth-service/src/test/java/dev/codearena/auth/KeyConfigTest.java`

```java
package dev.codearena.auth;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class KeyConfigTest {

    @Test
    void loadsPkcs8PemAndKeepsAStableKeyId() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        RSAKey first = KeyConfig.fromPem(pem);
        RSAKey second = KeyConfig.fromPem(pem);

        assertThat(first.isPrivate()).isTrue();
        assertThat(first.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(first.getKeyID()).isNotBlank().isEqualTo(second.getKeyID());   // same key -> same kid
        assertThat(first.toPublicJWK().isPrivate()).isFalse();                     // JWKS never leaks the private part
    }
}
```

## Check

```bash
./gradlew :services:auth-service:test :services:auth-service:bootJar
java -jar services/auth-service/build/libs/auth-service.jar      # from the repo root; port 9081
```

In another terminal:

```bash
curl -s -X POST localhost:9081/v1/auth/register -H 'content-type: application/json' \
  -d '{"email":"admin@codearena.local","password":"change-me-please","displayName":"admin"}'
# {"id":"01J...","email":"admin@codearena.local","displayName":"admin","roles":["ADMIN","USER"],...}
curl -s -X POST localhost:9081/v1/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@codearena.local","password":"change-me-please"}' | python3 -m json.tool
curl -s localhost:9081/.well-known/jwks.json | python3 -m json.tool       # one RSA public key, no "d"
```

Paste the access token into a local JWT decoder (for example `python3 -c "import sys,base64,json;
print(json.loads(base64.urlsafe_b64decode(sys.argv[1].split('.')[1]+'==')))" <token>`) and confirm the `roles`
claim. Stop the jar with Ctrl+C; chapter 12 starts everything together.
