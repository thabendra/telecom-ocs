package com.thabendra.telecom.customer;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CU-1: the customer token this backend signs (HS256) and checks itself. */
class CustomerTokensTest {

    private static final String SECRET = "test-customer-token-secret-32-bytes!";

    @Test
    void anIssuedTokenIsForTheNumberAndOnlyThisApi() {
        CustomerTokens tokens = tokens(SECRET, Duration.ofMinutes(15));

        Jwt jwt = tokens.decoder().decode(tokens.issue("94771234567").accessToken());

        assertThat(jwt.getSubject()).isEqualTo("94771234567");
        assertThat(jwt.getAudience()).containsExactly("customer-api");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theCallerIsTheNumberWithOnlyTheCustomerRole() {
        CustomerTokens tokens = tokens(SECRET, Duration.ofMinutes(15));

        Authentication caller = tokens.authenticationManager()
                .authenticate(new BearerTokenAuthenticationToken(tokens.issue("94771234567").accessToken()));

        assertThat(caller.getName()).isEqualTo("94771234567");
        assertThat(caller.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(CustomerTokens.ROLE);
    }

    @Test
    void aTokenFromAnotherKeyIsRefused() {
        String forged = tokens("another-secret-of-at-least-32-bytes!!", Duration.ofMinutes(15))
                .issue("94771234567").accessToken();

        assertThatThrownBy(() -> tokens(SECRET, Duration.ofMinutes(15)).decoder().decode(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {   // past exp plus the 60 s clock skew
        Instant past = Instant.now().minus(Duration.ofMinutes(20));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("telecom-ocs-customer-login").audience(List.of("customer-api")).subject("94771234567")
                .issuedAt(past).expiresAt(past.plus(Duration.ofMinutes(15)))
                .build();
        SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String expired = new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThatThrownBy(() -> tokens(SECRET, Duration.ofMinutes(15)).decoder().decode(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aShortSecretStopsTheStart() {
        assertThatThrownBy(() -> tokens("too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static CustomerTokens tokens(String secret, Duration ttl) {
        return new CustomerTokens(new CustomerProperties(null,
                new CustomerProperties.Token("telecom-ocs-customer-login", "customer-api", secret, ttl)));
    }
}
