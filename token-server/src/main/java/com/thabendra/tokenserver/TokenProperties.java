package com.thabendra.tokenserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * telecom.token.* in application.yml.
 *
 * @param issuer         the iss claim and the server's public address
 * @param audience       the aud claim every access token carries
 * @param accessTokenTtl how long an access token is valid
 * @param clients        one OAuth2 client per gateway
 */
@ConfigurationProperties("telecom.token")
public record TokenProperties(String issuer, String audience, Duration accessTokenTtl, List<Client> clients) {

    /**
     * @param id      client_id, the same as the gateway's certificate name
     * @param subject the certificate subject that must authenticate as this client (tls_client_auth)
     * @param scopes  what this gateway may ask for
     */
    public record Client(String id, String subject, List<String> scopes) {
    }
}
