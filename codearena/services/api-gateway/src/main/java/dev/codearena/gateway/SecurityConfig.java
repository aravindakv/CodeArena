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