package com.thabendra.gatewaysim;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Charging over the backend's REST API (/charging/v1/sessions). Every request carries its
 * Idempotency-Key and the gateway's X-Client-Id, so a retry is replayed instead of charged again.
 */
public class RestChargingClient implements ChargingClient {

    private final RestClient http;
    private final String clientId;
    private final AccessTokens tokens;

    public RestChargingClient(RestClient.Builder builder, String backendUrl, String clientId) {
        this(builder, backendUrl, clientId, AccessTokens.NONE);
    }

    public RestChargingClient(RestClient.Builder builder, String backendUrl, String clientId, AccessTokens tokens) {
        this.http = builder.baseUrl(backendUrl).build();
        this.clientId = clientId;
        this.tokens = tokens;
    }

    @Override
    public Answer<Grant> start(String key, String msisdn, String app, long requestedMb) {
        return post("/charging/v1/sessions", key,
                Map.of("msisdn", msisdn, "app", app, "requestedMb", requestedMb), Grant.class);
    }

    @Override
    public Answer<Grant> update(String key, String sessionId, long requestNumber, long usedMb, long requestedMb) {
        return post("/charging/v1/sessions/" + sessionId + "/update", key,
                Map.of("requestNumber", requestNumber, "usedMb", usedMb, "requestedMb", requestedMb), Grant.class);
    }

    @Override
    public Answer<Release> release(String key, String sessionId, long requestNumber, long usedMb) {
        return post("/charging/v1/sessions/" + sessionId + "/release", key,
                Map.of("requestNumber", requestNumber, "usedMb", usedMb), Release.class);
    }

    private <T> Answer<T> post(String path, String key, Map<String, Object> body, Class<T> type) {
        return AccessTokens.refreshOnce(tokens, () -> send(path, key, body, type));
    }

    private <T> Answer<T> send(String path, String key, Map<String, Object> body, Class<T> type) {
        try {
            ResponseEntity<T> response = http.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key)
                    .header("X-Client-Id", clientId)
                    .headers(headers -> AccessTokens.bearer(headers, tokens.current()))
                    .body(body)
                    .retrieve()
                    .toEntity(type);
            HttpHeaders headers = response.getHeaders();
            return new Answer<>(response.getBody(), "true".equals(headers.getFirst("Idempotent-Replayed")));
        } catch (ResourceAccessException e) {
            throw BackendCallException.noAnswer(e);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            HttpHeaders headers = e.getResponseHeaders();
            throw new BackendCallException(status,
                    BackendCallException.parseRetryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER)),
                    "HTTP " + status + ": " + e.getResponseBodyAsString(), e);
        }
    }
}
