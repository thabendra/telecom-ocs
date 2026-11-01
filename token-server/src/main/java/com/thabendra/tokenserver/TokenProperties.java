package com.thabendra.tokenserver;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * telecom.token.* in application.yml.
 *
 * @param issuer         the iss claim and the server's public address
 * @param audience       the aud claim every access token carries
 * @param accessTokenTtl how long an access token is valid
 * @param clients        one OAuth2 client per gateway
 * @param signing        the signing keys and their rotation (SE-7)
 * @param revocation     the revocation list (SE-6)
 */
@ConfigurationProperties("telecom.token")
public record TokenProperties(String issuer, String audience, Duration accessTokenTtl, List<Client> clients,
                              Signing signing, Revocation revocation) {

    /**
     * @param id      client_id, the same as the gateway's certificate name
     * @param subject the certificate subject that must authenticate as this client (tls_client_auth)
     * @param scopes  what this gateway may ask for
     * @param enabled false locks the gateway out whichever certificate it shows (SE-6)
     */
    public record Client(String id, String subject, List<String> scopes, @DefaultValue("true") boolean enabled) {
    }

    /**
     * @param directory    where the keys are kept
     * @param rotateEvery  how often a new key is made
     * @param publishAhead a new key is published this long before it signs; longer than the backends' refresh
     * @param retireAfter  an old key stays this long after it stopped signing; longer than a token lives
     * @param checkEvery   how often the store looks at the clock
     */
    public record Signing(String directory, Duration rotateEvery, Duration publishAhead, Duration retireAfter,
                          Duration checkEvery) {
    }

    /**
     * @param location    the CRL file (scripts/revoke-gateway.sh)
     * @param reloadEvery how soon a revocation works
     */
    public record Revocation(String location, Duration reloadEvery) {
    }
}
