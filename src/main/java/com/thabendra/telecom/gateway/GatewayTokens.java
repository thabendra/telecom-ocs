package com.thabendra.telecom.gateway;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** What makes a gateway access token valid (SE-3), and how it is tied to a certificate (SE-4). */
public final class GatewayTokens {

    private GatewayTokens() {
    }

    /**
     * SE-3: issued by our token server (iss), for this API (aud), not expired or used too early
     * (exp / nbf, with Spring's default 60 s clock skew). The signature is checked by the decoder.
     * SE-4: Spring Security's defaults also include X509CertificateThumbprintValidator: a token that
     * carries cnf.x5t#S256 must match the request's client certificate. A token without cnf passes that
     * check, so {@link GatewayBindingFilter} additionally requires every gateway token to be bound.
     */
    public static OAuth2TokenValidator<Jwt> validator(GatewayProperties.Token token) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(token.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(token.audience())));
    }

    /** SE-4: the cnf.x5t#S256 claim the token server put in, or null when the token isn't bound. */
    public static String boundThumbprint(Jwt jwt) {
        Map<String, Object> confirmation = jwt.getClaimAsMap("cnf");
        Object thumbprint = confirmation == null ? null : confirmation.get("x5t#S256");
        return thumbprint == null ? null : thumbprint.toString();
    }

    /** RFC 8705: base64url (no padding) of the SHA-256 of the certificate's DER bytes. */
    public static String thumbprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
