# 05 · API gateway: routing, security, rate limiting, circuit breakers

Every client, web or mobile, talks only to the gateway at `/api/v1/...`. It strips `/api` and forwards
`/v1/...` to the right service. It is built on Spring Cloud Gateway (reactive, Netty).

| Concern | How |
| --- | --- |
| Routing | One route per service area. Order matters: the more specific `auth-login` and `submissions-post` routes come first |
| Authentication | Verifies every JWT signature with auth-service's JWKS (cached) and the `aud` claim |
| Authorization | First line of role checks: `/api/v1/admin/**` needs ADMIN, submitting needs USER or ADMIN. Services check again |
| Rate limiting | Redis token buckets: login/register 1 per second per IP (burst 10); submissions 1 per second per user (burst 5) |
| Circuit breakers | Resilience4j around problems, submissions, contests: when a service fails or is slow, the breaker opens and a fallback answers 503 with `Retry-After` |
| Hardening | Forged `X-User-*` headers stripped, request size capped at 128 KB, `X-Frame-Options: DENY`, CSP, logout deny list, `X-Request-Id` for log correlation |
| Mobile-ready | Versioned paths (`/api/v1`), Problem Details errors with stable codes, `Idempotency-Key` on submissions, refresh token accepted in the body |

Spring Cloud Gateway 5 (Spring Cloud 2025.1, Boot 4) uses the starter `spring-cloud-starter-gateway-server-webflux`
and the configuration prefix `spring.cloud.gateway.server.webflux`. Tutorials using `spring.cloud.gateway.routes`
are for older versions, and that prefix now fails silently: the gateway starts with no routes. The circuit breaker
filter needs the *reactor* Resilience4j starter on a WebFlux gateway.

**File:** `services/api-gateway/build.gradle.kts`

```kotlin
plugins { id("codearena.service") }

dependencies {
    // Spring Cloud 2025.0 renamed the reactive gateway starter (config prefix: spring.cloud.gateway.server.webflux)
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
}
```

**File:** `services/api-gateway/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:9080}

spring:
  application:
    name: api-gateway
  config:
    import: classpath:codearena-common.yml
  threads:
    virtual:
      enabled: false          # reactive (Netty) app
  cloud:
    gateway:
      server:
        webflux:
          httpclient:
            connect-timeout: 2000
            response-timeout: 10s
          default-filters:
            - name: RequestSize
              args:
                maxSize: 128KB          # code is capped at 64 KB; this leaves room for JSON
          routes:
            # ---- auth: login/register get a strict per-IP limit against credential stuffing
            - id: auth-login
              uri: ${AUTH_URL:http://localhost:9081}
              predicates:
                - Path=/api/v1/auth/login,/api/v1/auth/register
              filters:
                - StripPrefix=1
                - name: RequestRateLimiter
                  args:
                    key-resolver: "#{@ipKeyResolver}"
                    redis-rate-limiter.replenishRate: 1
                    redis-rate-limiter.burstCapacity: 10
            - id: auth
              uri: ${AUTH_URL:http://localhost:9081}
              predicates:
                - Path=/api/v1/auth/**,/api/v1/admin/users/**
              filters:
                - StripPrefix=1
            # ---- problems (+ admin problem management and AI ingestion)
            - id: problems
              uri: ${PROBLEM_URL:http://localhost:9082}
              predicates:
                - Path=/api/v1/problems/**,/api/v1/languages,/api/v1/admin/problems/**,/api/v1/admin/ingestion/**
              filters:
                - StripPrefix=1
                - name: CircuitBreaker
                  args:
                    name: problems
                    fallbackUri: forward:/fallback/problems
            # ---- submissions: POST is rate limited per user (polling GETs are not)
            - id: submissions-post
              uri: ${SUBMISSION_URL:http://localhost:9083}
              predicates:
                - Path=/api/v1/submissions,/api/v1/submissions/**
                - Method=POST
              filters:
                - StripPrefix=1
                - name: RequestRateLimiter
                  args:
                    key-resolver: "#{@userKeyResolver}"
                    redis-rate-limiter.replenishRate: 1
                    redis-rate-limiter.burstCapacity: 5
                - name: CircuitBreaker
                  args:
                    name: submissions
                    fallbackUri: forward:/fallback/busy
            - id: submissions
              uri: ${SUBMISSION_URL:http://localhost:9083}
              predicates:
                - Path=/api/v1/submissions,/api/v1/submissions/**
              filters:
                - StripPrefix=1
            # ---- contests and leaderboards
            - id: contests
              uri: ${CONTEST_URL:http://localhost:9084}
              predicates:
                - Path=/api/v1/contests,/api/v1/contests/**,/api/v1/admin/contests,/api/v1/admin/contests/**
              filters:
                - StripPrefix=1
                - name: CircuitBreaker
                  args:
                    name: contests
                    fallbackUri: forward:/fallback/busy
            # ---- per-user stats and admin analytics
            - id: analytics
              uri: ${ANALYTICS_URL:http://localhost:9085}
              predicates:
                - Path=/api/v1/me/**,/api/v1/admin/analytics/**
              filters:
                - StripPrefix=1

resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 80
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
  timelimiter:
    configs:
      default:
        timeout-duration: 8s

codearena:
  cors-origins: ${CORS_ORIGINS:http://localhost:5173}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,gateway,circuitbreakers
```

**File:** `services/api-gateway/src/main/java/dev/codearena/gateway/GatewayApplication.java`

```java
package dev.codearena.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

**File:** `services/api-gateway/src/main/java/dev/codearena/gateway/SecurityConfig.java`

```java
package dev.codearena.gateway;

import dev.codearena.common.security.Roles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The gateway is the first line of authorization. Services repeat the checks (defence in depth).
 *  - anonymous: login/register/refresh, browsing problems, viewing contests and leaderboards
 *  - USER or ADMIN: submitting code, own stats
 *  - ADMIN only: everything under /api/v1/admin/**
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(x -> x
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/api/v1/auth/**", "/fallback/**", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/problems", "/api/v1/problems/**", "/api/v1/languages",
                                "/api/v1/contests", "/api/v1/contests/*", "/api/v1/contests/*/leaderboard").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole(Roles.ADMIN)
                        .pathMatchers("/api/v1/submissions", "/api/v1/submissions/**", "/api/v1/me/**",
                                "/api/v1/contests/*/register", "/api/v1/contests/*/leaderboard/me")
                        .hasAnyRole(Roles.USER, Roles.ADMIN)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Roles.reactiveJwtConverter())))
                .headers(h -> h
                        .frameOptions(f -> f.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    /** Only needed when the web app is served from a different origin than the API (not with the Vite proxy). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${codearena.cors-origins}") List<String> origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("Retry-After", "X-RateLimit-Remaining", "X-Request-Id"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
```

**File:** `services/api-gateway/src/main/java/dev/codearena/gateway/KeyResolvers.java`

```java
package dev.codearena.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/** Rate-limit buckets: per user when logged in, per client IP otherwise. */
@Configuration
class KeyResolvers {

    static String clientIp(ServerWebExchange ex) {
        String xff = ex.getRequest().getHeaders().getFirst("X-Forwarded-For");   // set by a load balancer, if any
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        InetSocketAddress remote = ex.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Bean
    @Primary
    KeyResolver userKeyResolver() {
        return ex -> ex.getPrincipal().map(Principal::getName).map(n -> "u:" + n)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(ex)));
    }

    @Bean
    KeyResolver ipKeyResolver() {
        return ex -> Mono.just("ip:" + clientIp(ex));
    }
}
```

The identity filter has one subtle point worth understanding. `chain.filter(...)` returns a `Mono<Void>` that
completes *empty*. Writing `principal.flatMap(... chain.filter ...).switchIfEmpty(chain.filter ...)` therefore runs
the chain twice for every logged-in request. Wrapping the principal in an `Optional` first avoids it.

**File:** `services/api-gateway/src/main/java/dev/codearena/gateway/IdentityFilter.java`

```java
package dev.codearena.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 1. Drops identity headers a client may have forged.
 * 2. Rejects access tokens revoked by logout (deny:<jti> in Redis).
 * 3. Adds verified X-User-Id / X-User-Roles and an X-Request-Id for log correlation.
 */
@Component
class IdentityFilter implements GlobalFilter, Ordered {
    private final ReactiveStringRedisTemplate redis;

    IdentityFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
        String requestId = Optional.ofNullable(ex.getRequest().getHeaders().getFirst("X-Request-Id"))
                .orElse(UUID.randomUUID().toString());
        ServerHttpRequest cleaned = ex.getRequest().mutate()
                .headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Roles"); h.set("X-Request-Id", requestId); })
                .build();
        ex.getResponse().getHeaders().set("X-Request-Id", requestId);

        // Wrap in Optional first: chain.filter() completes empty, so a switchIfEmpty after flatMap
        // would run the chain a second time for every authenticated request.
        return ex.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybeAuth -> {
                    if (maybeAuth.isEmpty()) return chain.filter(ex.mutate().request(cleaned).build());
                    JwtAuthenticationToken auth = maybeAuth.get();
                    String jti = auth.getToken().getId();
                    Mono<Boolean> denied = jti == null ? Mono.just(false)
                            : redis.hasKey("deny:" + jti).onErrorReturn(false);   // Redis down: fail open, tokens are short-lived
                    return denied.flatMap(d -> {
                        if (d) {
                            ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return ex.getResponse().setComplete();
                        }
                        List<String> roles = Optional.ofNullable(auth.getToken().getClaimAsStringList("roles")).orElse(List.of());
                        ServerHttpRequest withUser = cleaned.mutate()
                                .header("X-User-Id", auth.getName())
                                .header("X-User-Roles", String.join(",", roles))
                                .build();
                        return chain.filter(ex.mutate().request(withUser).build());
                    });
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
```

**File:** `services/api-gateway/src/main/java/dev/codearena/gateway/FallbackController.java`

```java
package dev.codearena.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR;

/**
 * Answers when a circuit breaker is open or a downstream call fails or times out.
 * The client only sees a generic 503; the real cause is logged here so you can find it.
 */
@RestController
class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback/problems")
    ResponseEntity<String> problems(ServerWebExchange exchange) {
        logCause(exchange);
        return busy("PROBLEMS_UNAVAILABLE", "problem service is temporarily unavailable");
    }

    @RequestMapping("/fallback/busy")
    ResponseEntity<String> busy(ServerWebExchange exchange) {
        logCause(exchange);
        return busy("TRY_AGAIN", "the service is busy, please retry in a few seconds");
    }

    private static void logCause(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        Object original = exchange.getAttribute(GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        log.warn("fallback for {} {}: {}", exchange.getRequest().getMethod(), original,
                cause == null ? "circuit open or unknown" : cause.toString());
    }

    private static ResponseEntity<String> busy(String code, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":503,\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
```

## Check (with auth-service running)

```bash
./gradlew :services:api-gateway:bootJar && java -jar services/api-gateway/build/libs/api-gateway.jar
TOKEN=$(curl -s -X POST localhost:9080/api/v1/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@codearena.local","password":"change-me-please"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s localhost:9080/api/v1/auth/me -H "authorization: Bearer $TOKEN"
curl -s localhost:9080/actuator/gateway/routes -H "authorization: Bearer $TOKEN" | python3 -m json.tool | grep '"route_id"'   # 7 routes
for i in $(seq 1 15); do curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:9080/api/v1/auth/login \
  -H 'content-type: application/json' -d '{"email":"x@y.z","password":"wrong-password"}'; done; echo
# 401 ten times, then 429 (rate limited)
```
