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