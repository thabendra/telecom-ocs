package com.thabendra.telecom;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.thabendra.telecom.gateway.GatewayProperties;
import com.thabendra.telecom.gateway.GatewayTokens;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Access tokens for tests, shaped exactly like the token server's (iss, aud, sub, scope, cnf, 5 min).
 * They are signed with a test key; {@link Decoder} makes the backend check them with that key and the
 * same validation rules as production ({@link GatewayTokens#validator}), so no token server is needed.
 */
public final class TestTokens {

    public static final String ISSUER = "https://localhost:9443";
    public static final String AUDIENCE = "charging-api";

    private static final KeyPair KEY = rsa();
    private static final KeyPair OTHER_KEY = rsa();

    private TestTokens() {
    }

    /** A valid token for this gateway, bound to its certificate in certs/. */
    public static String token(String gateway) {
        return token(gateway, claims -> { });
    }

    /** A token for this gateway with some claims changed, e.g. claims.expiresAt(...). */
    public static String token(String gateway, Consumer<JwtClaimsSet.Builder> changes) {
        return sign(KEY, gateway, changes);
    }

    /** Looks right, but signed by a key the backend doesn't trust. */
    public static String tokenSignedByAnotherKey(String gateway) {
        return sign(OTHER_KEY, gateway, claims -> { });
    }

    /** cnf.x5t#S256 claim for a certificate file in certs/ (SE-4). */
    public static Map<String, Object> boundTo(String certificate) {
        return Map.of("x5t#S256", GatewayTokens.thumbprint(certificate(certificate)));
    }

    private static String sign(KeyPair key, String gateway, Consumer<JwtClaimsSet.Builder> changes) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(gateway)
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plusSeconds(300))
                .claim("client_id", gateway)
                .claim("scope", List.of("charging:write", "notify:read"))
                .claim("cnf", boundTo(gateway));
        changes.accept(claims);
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) key.getPublic()).privateKey((RSAPrivateKey) key.getPrivate())
                .keyID("test").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    private static X509Certificate certificate(String name) {
        Path pem = Path.of("certs", name + ".pem");
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException(pem + " is missing: run scripts/generate-dev-certs.sh", e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checks gateway tokens with the test key instead of the token server's /oauth2/jwks. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Decoder {

        @Bean
        @Primary
        JwtDecoder testGatewayTokenDecoder(GatewayProperties gateways) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
            decoder.setJwtValidator(GatewayTokens.validator(gateways.token()));
            return decoder;
        }
    }
}
