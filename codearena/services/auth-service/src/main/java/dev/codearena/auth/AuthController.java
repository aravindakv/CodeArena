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