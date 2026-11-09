package dev.codearena.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;

/**
 * The two roles of the platform and the JWT -> Spring Security mapping shared by every service.
 * A token's "roles" claim ["USER","ADMIN"] becomes authorities ROLE_USER, ROLE_ADMIN,
 * so hasRole('ADMIN') works everywhere.
 */
public final class Roles {
    private Roles() {}

    public static final String USER = "USER";
    public static final String ADMIN = "ADMIN";

    /** For servlet (Spring MVC) services. */
    public static JwtAuthenticationConverter jwtConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** For the reactive gateway. */
    public static ReactiveJwtAuthenticationConverterAdapter reactiveJwtConverter() {
        Converter<Jwt, AbstractAuthenticationToken> c = jwtConverter();
        return new ReactiveJwtAuthenticationConverterAdapter(c);
    }
}