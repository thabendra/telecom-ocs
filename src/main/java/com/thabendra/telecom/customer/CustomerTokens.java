package com.thabendra.telecom.customer;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The customer's access token (CU-1): a JWT this backend signs itself after a correct login code.
 * <pre>
 *   iss  telecom-ocs-customer-login    aud  customer-api
 *   sub  the customer's number         exp  15 minutes
 * </pre>
 * Only this backend makes and checks these tokens, so one shared HS256 key is enough (the gateways'
 * and staff's tokens come from other servers and are checked with their public keys). A customer
 * always gets exactly ROLE_CUSTOMER: roles are never read from the token, so nobody can add a role.
 */
@Component
public class CustomerTokens {

    public static final String ROLE = "ROLE_CUSTOMER";

    /** What the login answers with. */
    public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
    }

    private final CustomerProperties.Token properties;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public CustomerTokens(CustomerProperties customers) {
        this.properties = customers.token();
        SecretKey key = key(properties.secret());
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(properties.audience()))));
        this.decoder = nimbus;
    }

    public IssuedToken issue(String msisdn) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(msisdn)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(properties.ttl()))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, "Bearer", properties.ttl().toSeconds());
    }

    public String issuer() {
        return properties.issuer();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    /** Checks a customer token; the caller's name is the number (sub), the only authority ROLE_CUSTOMER. */
    public AuthenticationManager authenticationManager() {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(jwt ->
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(ROLE)), jwt.getSubject()));
        return new ProviderManager(provider);
    }

    private static SecretKey key(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("telecom.customers.token.secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
