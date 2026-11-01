package com.thabendra.telecom.startup;

import com.thabendra.telecom.cache.CacheRedis;
import com.thabendra.telecom.charging.SessionCache;
import com.thabendra.telecom.charging.UsageWindow;
import com.thabendra.telecom.idempotency.IdempotencyStore;
import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.ratelimit.GatewayRateLimiter;
import com.thabendra.telecom.resilience.RedisBreaker;
import com.thabendra.telecom.resilience.SigningKeysFactory;
import com.thabendra.telecom.subscriber.SubscriberService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The warm-up touches nothing real, never stops a start, and leaves the breakers clean. */
class StartupWarmupTest {

    private final SigningKeysFactory signingKeys = mock(SigningKeysFactory.class);
    private final GatewayRateLimiter rateLimiter = mock(GatewayRateLimiter.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final SessionCache sessions = mock(SessionCache.class);
    private final UsageWindow usage = mock(UsageWindow.class);
    private final GatewayCommands commands = mock(GatewayCommands.class);
    private final SubscriberService subscribers = mock(SubscriberService.class);
    private final RedisBreaker dataBreaker = mock(RedisBreaker.class);
    private final RedisBreaker cacheBreaker = mock(RedisBreaker.class);
    private final CacheRedis cache = mock(CacheRedis.class);

    private StartupWarmup warmup;

    @BeforeEach
    void setUp() {
        when(cache.breaker()).thenReturn(cacheBreaker);
        when(dataBreaker.state()).thenReturn(CircuitBreaker.State.CLOSED);
        warmup = new StartupWarmup(signingKeys, rateLimiter, idempotency, sessions, usage, commands, subscribers,
                dataBreaker, cache, Duration.ofMillis(100));
    }

    @Test
    void itRunsWhatARequestRunsUnderANameNobodyHas() throws Exception {
        warmup.run(null);

        verify(signingKeys).refreshAll();
        verify(rateLimiter, atLeastOnce()).tryAcquire(org.mockito.ArgumentMatchers.eq("warmup"), any());
        verify(sessions, atLeastOnce()).find("warmup");
        verify(usage, atLeastOnce()).total("warmup");
        verify(commands, atLeastOnce()).poll("warmup");
        verify(subscribers, atLeastOnce()).listPlans();
        verify(idempotency, atLeastOnce()).release(org.mockito.ArgumentMatchers.startsWith("idem:warmup:"));
    }

    @Test
    void realTrafficStartsWithCleanBreakers() throws Exception {
        warmup.run(null);

        verify(dataBreaker).reset();
        verify(cacheBreaker).reset();
    }

    @Test
    void aBreakerThatOpensDuringTheWarmUpIsClosedAgainSoItKeepsWarming() throws Exception {
        when(dataBreaker.state()).thenReturn(CircuitBreaker.State.OPEN);

        warmup.run(null);

        verify(dataBreaker, atLeast(2)).reset();   // during it, and at the end
    }

    @Test
    void aFailureNeverStopsTheStart() {
        doThrow(new IllegalStateException("key server down")).when(signingKeys).refreshAll();
        when(rateLimiter.tryAcquire(anyString(), any())).thenThrow(new QueryTimeoutException("Redis timed out"));
        when(commands.poll(anyString())).thenThrow(new IllegalStateException("Oracle down"));
        when(subscribers.listPlans()).thenThrow(new IllegalStateException("Oracle down"));

        assertThatCode(() -> warmup.run(null)).doesNotThrowAnyException();

        verify(cacheBreaker).reset();
        verify(commands, never()).ack(anyString(), anyString());
    }
}
