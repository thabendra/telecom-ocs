package com.thabendra.gatewaysim;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP client for the backend's /charging/v1/sessions API, the way a gateway calls it:
 * every request carries its own Idempotency-Key and the gateway's X-Client-Id.
 * A 409 with Retry-After means "the first copy of this request is still running": the client waits
 * Retry-After plus jitter and sends the same request again with the same key, at most MAX_RETRIES times.
 */
public class ChargingClient {

    static final int MAX_RETRIES = 3;
    static final long MAX_JITTER_MS = 250;

    /** Same shape as the backend's grant answer. */
    public record Grant(String sessionId, String result, long grantedMb, Integer bandwidthKbps, String source,
                        String packageCode, Integer validitySeconds, String action) {

        public boolean allowed() {
            return "ALLOW".equals(result);
        }
    }

    public record Release(String sessionId, long totalUsedMb) {
    }

    /** One answer plus whether the backend replayed a stored answer for this key. */
    public record Answer<T>(T body, boolean replayed) {
    }

    /** How the client waits between retries; tests pass one that records instead of sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient http;
    private final String clientId;
    private final Sleeper sleeper;

    public ChargingClient(RestClient.Builder builder, String backendUrl, String clientId) {
        this(builder, backendUrl, clientId, duration -> Thread.sleep(duration));
    }

    public ChargingClient(RestClient.Builder builder, String backendUrl, String clientId, Sleeper sleeper) {
        this.http = builder.baseUrl(backendUrl).build();
        this.clientId = clientId;
        this.sleeper = sleeper;
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public Answer<Grant> start(String key, String msisdn, String app, long requestedMb) {
        return post("/charging/v1/sessions", key,
                Map.of("msisdn", msisdn, "app", app, "requestedMb", requestedMb), Grant.class);
    }

    public Answer<Grant> update(String key, String sessionId, long requestNumber, long usedMb, long requestedMb) {
        return post("/charging/v1/sessions/" + sessionId + "/update", key,
                Map.of("requestNumber", requestNumber, "usedMb", usedMb, "requestedMb", requestedMb), Grant.class);
    }

    public Answer<Release> release(String key, String sessionId, long requestNumber, long usedMb) {
        return post("/charging/v1/sessions/" + sessionId + "/release", key,
                Map.of("requestNumber", requestNumber, "usedMb", usedMb), Release.class);
    }

    private <T> Answer<T> post(String path, String key, Map<String, Object> body, Class<T> type) {
        for (int retry = 0; ; retry++) {
            try {
                return send(path, key, body, type);
            } catch (HttpClientErrorException.Conflict conflict) {
                // a 409 without Retry-After (e.g. "already processed") is final, not "try again"
                Duration retryAfter = retryAfter(conflict);
                if (retryAfter == null || retry == MAX_RETRIES) {
                    throw conflict;
                }
                waitBeforeRetry(retryAfter);
            }
        }
    }

    private <T> Answer<T> send(String path, String key, Map<String, Object> body, Class<T> type) {
        ResponseEntity<T> response = http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-Client-Id", clientId)
                .body(body)
                .retrieve()
                .toEntity(type);
        HttpHeaders headers = response.getHeaders();
        return new Answer<>(response.getBody(), "true".equals(headers.getFirst("Idempotent-Replayed")));
    }

    private static Duration retryAfter(HttpClientErrorException conflict) {
        HttpHeaders headers = conflict.getResponseHeaders();
        String seconds = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (seconds == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(seconds.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // jitter spreads the retries of many gateways so they don't all hit the backend at the same moment
    private void waitBeforeRetry(Duration retryAfter) {
        Duration jitter = Duration.ofMillis(ThreadLocalRandom.current().nextLong(MAX_JITTER_MS + 1));
        try {
            sleeper.sleep(retryAfter.plus(jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
