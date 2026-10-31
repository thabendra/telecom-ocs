package com.thabendra.gatewaysim;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.function.Supplier;

/**
 * The push channel from the gateway's side (PU-3): poll the backend for BLOCK / UNBLOCK commands and
 * ack each one. Outbound calls only, over the same mTLS connection and token as charging (the token
 * needs notify:read).
 */
public class CommandClient {

    /** A command as the backend sends it; id is the key to apply a redelivered command only once. */
    public record Command(String id, String msisdn, String action, String reason, Integer bandwidthKbps,
                          int attempt) {
    }

    private final RestClient http;
    private final AccessTokens tokens;

    public CommandClient(RestClient.Builder builder, String backendUrl, AccessTokens tokens) {
        this.http = builder.baseUrl(backendUrl).build();
        this.tokens = tokens;
    }

    public List<Command> poll() {
        return AccessTokens.refreshOnce(tokens, () -> call(() -> http.get()
                .uri("/charging/v1/commands")
                .headers(headers -> AccessTokens.bearer(headers, tokens.current()))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Command>>() { })));
    }

    public void ack(String commandId) {
        AccessTokens.refreshOnce(tokens, () -> call(() -> http.post()
                .uri("/charging/v1/commands/{id}/ack", commandId)
                .headers(headers -> AccessTokens.bearer(headers, tokens.current()))
                .retrieve()
                .toBodilessEntity()));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw BackendCallException.noAnswer(e);
        } catch (RestClientResponseException e) {
            HttpHeaders headers = e.getResponseHeaders();
            throw new BackendCallException(e.getStatusCode().value(),
                    BackendCallException.parseRetryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER)),
                    "HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        }
    }
}
