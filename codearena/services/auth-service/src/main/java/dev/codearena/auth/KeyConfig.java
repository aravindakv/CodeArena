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