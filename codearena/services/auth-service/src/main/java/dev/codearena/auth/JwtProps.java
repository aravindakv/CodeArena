package dev.codearena.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("codearena.jwt")
public record JwtProps(String issuer, String audience, Duration accessTtl, Duration adminAccessTtl,
                       Duration refreshTtl, String privateKeyFile) {
}