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