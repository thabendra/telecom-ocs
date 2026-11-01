package com.thabendra.telecom.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ID-9: a request that failed must not leave its lock behind, or the gateway's retry gets 409 until the lock expires.
 * Found by the coverage report (TE-5): no test ever came through these paths.
 */
class IdempotencyFilterFailureTest {

    private static final String KEY = "idem:pgw-test:key-1";

    private IdempotencyStore store;
    private IdempotencyFilter filter;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        when(store.claimOrFind(anyString(), anyString())).thenReturn(Optional.empty());
        filter = new IdempotencyFilter(store, new IdempotencyProperties(List.of("/charging/v1/sessions"), "/ws",
                List.of("StartSessionRequest"), Duration.ofHours(24), Duration.ofSeconds(30)), new ObjectMapper());
        request = new MockHttpServletRequest("POST", "/charging/v1/sessions");
        request.setRequestURI("/charging/v1/sessions");
        request.addHeader(IdempotencyFilter.KEY_HEADER, "key-1");
        request.addHeader(IdempotencyFilter.CLIENT_HEADER, "pgw-test");
        request.setContent("{}".getBytes());
    }

    @Test
    void aServerErrorFreesTheKeySoTheRetryRunsAgain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) unwrap(res)).setStatus(503));

        verify(store).release(KEY);
        verify(store, never()).saveResult(anyString(), any());
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void aCrashFreesTheKeyAndTheErrorGoesOn() {
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("database down");
        })).hasMessage("database down");

        verify(store).release(KEY);
    }

    @Test
    void anAnswerIsKeptAndTheKeyIsNotFreed() throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(store).saveResult(eq(KEY), any());
        verify(store, never()).release(anyString());
    }

    @Test
    void redisGoneWhileFreeingTheKeyDoesNotChangeTheAnswer() throws Exception {
        doThrow(new QueryTimeoutException("Redis timed out")).when(store).release(KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) unwrap(res)).setStatus(500));

        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void redisGoneWhileKeepingTheAnswerDoesNotChangeIt() throws Exception {
        doThrow(new QueryTimeoutException("Redis timed out")).when(store).saveResult(eq(KEY), any());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("charged"));

        assertThat(response.getContentAsString()).isEqualTo("charged");
    }

    @Test
    void aMadeUpClientNameFallsBackToAnonymous() throws Exception {   // it becomes part of a Redis key
        MockHttpServletRequest strange = new MockHttpServletRequest("POST", "/charging/v1/sessions");
        strange.setRequestURI("/charging/v1/sessions");
        strange.addHeader(IdempotencyFilter.KEY_HEADER, "key-1");
        strange.addHeader(IdempotencyFilter.CLIENT_HEADER, "../../other client");
        strange.setContent("{}".getBytes());

        filter.doFilter(strange, new MockHttpServletResponse(), (req, res) -> { });

        verify(store).claimOrFind(eq("idem:anonymous:key-1"), anyString());
    }

    // the filter wraps the response to capture it
    private static jakarta.servlet.ServletResponse unwrap(jakarta.servlet.ServletResponse response) {
        return ((jakarta.servlet.ServletResponseWrapper) response).getResponse();
    }
}
