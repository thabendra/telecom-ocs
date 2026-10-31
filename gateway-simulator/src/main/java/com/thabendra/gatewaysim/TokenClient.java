package com.thabendra.gatewaysim;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;

/**
 * Gets access tokens from the token server with the OAuth2 client credentials grant (SE-2). The HTTP
 * client shows this gateway's certificate, which is how the token server knows the client
 * (tls_client_auth, no secret), and why the token comes back bound to that certificate (SE-4).
 * A token is reused until shortly before it expires.
 */
public class TokenClient implements AccessTokens {

    /** Fetch a new token this long before the old one expires, so a call never carries a dying token. */
    static final Duration REFRESH_EARLY = Duration.ofSeconds(30);

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {
    }

    private final RestClient http;
    private final String tokenUrl;
    private final String clientId;
    private final String scope;
    private final Clock clock;

    private String token;
    private Instant refreshAt = Instant.MIN;

    public TokenClient(RestClient http, String tokenUrl, String clientId, String scope, Clock clock) {
        this.http = http;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.scope = scope;
        this.clock = clock;
    }

    @Override
    public synchronized String current() {
        if (token == null || !clock.instant().isBefore(refreshAt)) {
            TokenResponse fresh = fetch();
            token = fresh.accessToken();
            refreshAt = clock.instant().plusSeconds(fresh.expiresIn()).minus(REFRESH_EARLY);
        }
        return token;
    }

    @Override
    public synchronized void invalidate() {
        token = null;
    }

    private TokenResponse fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);   // tls_client_auth: the id is sent, the certificate proves it
        form.add("scope", scope);
        try {
            TokenResponse response = http.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new BackendCallException(502, null, "Token server sent no access_token", null);
            }
            return response;
        } catch (ResourceAccessException | CancellationException e) {   // CancellationException: see noAnswer
            throw BackendCallException.noAnswer(e);
        } catch (RestClientResponseException e) {
            HttpHeaders headers = e.getResponseHeaders();
            throw new BackendCallException(e.getStatusCode().value(),
                    BackendCallException.parseRetryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER)),
                    "Token server: HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        }
    }
}
