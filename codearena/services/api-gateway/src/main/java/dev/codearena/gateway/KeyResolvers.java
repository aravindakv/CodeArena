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