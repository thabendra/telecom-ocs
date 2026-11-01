package com.thabendra.telecom.startup;

import com.thabendra.telecom.cache.CacheRedis;
import com.thabendra.telecom.charging.SessionCache;
import com.thabendra.telecom.charging.UsageWindow;
import com.thabendra.telecom.idempotency.IdempotencyStore;
import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.ratelimit.GatewayRateLimiter;
import com.thabendra.telecom.ratelimit.RateLimitProperties;
import com.thabendra.telecom.resilience.RedisBreaker;
import com.thabendra.telecom.resilience.SigningKeysFactory;
import com.thabendra.telecom.subscriber.SubscriberService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A fresh JVM is slow for its first seconds, and a load test showed what that costs a copy that gets traffic at once:
 * slots held long enough to shed calls, and Redis calls timing out. So before this copy says it is ready (Spring Boot
 * sets readiness after the runners, and the probe keeps traffic away until then), it runs what a request runs: the
 * signing keys, the Redis scripts, the database reads.
 * <p>
 * Nothing it touches is real: keys named warmup, no gateway or subscriber of that name, and no charge. It can't warm
 * mTLS handshakes or the security filters, as those need a gateway's certificate. A failure here never stops the start:
 * a copy with Redis down still has its fallbacks.
 */
@Component
@ConditionalOnProperty(name = "telecom.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class StartupWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);
    private static final String NAME = "warmup";
    private static final int THREADS = 8;
    private static final RateLimitProperties.Limit UNLIMITED = new RateLimitProperties.Limit(1_000_000, 1_000_000);

    private final SigningKeysFactory signingKeys;
    private final GatewayRateLimiter rateLimiter;
    private final IdempotencyStore idempotency;
    private final SessionCache sessions;
    private final UsageWindow usage;
    private final GatewayCommands commands;
    private final SubscriberService subscribers;
    private final RedisBreaker dataBreaker;
    private final CacheRedis cache;
    private final Duration duration;

    public StartupWarmup(SigningKeysFactory signingKeys, GatewayRateLimiter rateLimiter, IdempotencyStore idempotency,
                         SessionCache sessions, UsageWindow usage, GatewayCommands commands,
                         SubscriberService subscribers, RedisBreaker dataBreaker, CacheRedis cache,
                         @Value("${telecom.warmup.duration:5s}") Duration duration) {
        this.signingKeys = signingKeys;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.sessions = sessions;
        this.usage = usage;
        this.commands = commands;
        this.subscribers = subscribers;
        this.dataBreaker = dataBreaker;
        this.cache = cache;
        this.duration = duration;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        long began = System.nanoTime();
        safely(signingKeys::refreshAll);   // the first token no longer waits for them, with every other request behind it

        long end = began + duration.toNanos();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread(() -> {
                while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) {
                    oneRound();
                }
            }, "warmup-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // cold calls may have looked slow: that says nothing about Redis, so real traffic starts with a clean count
        dataBreaker.reset();
        cache.breaker().reset();
        log.info("Warmed up in {} ms", (System.nanoTime() - began) / 1_000_000);
    }

    private void oneRound() {
        // these cold calls can open the breaker in the first second, and then they warm nothing: keep it closed
        if (dataBreaker.state() != CircuitBreaker.State.CLOSED) {
            dataBreaker.reset();
        }
        safely(() -> rateLimiter.tryAcquire(NAME, UNLIMITED));
        safely(() -> {
            String key = "idem:" + NAME + ":" + UUID.randomUUID();
            idempotency.claimOrFind(key, NAME);
            idempotency.release(key);
        });
        safely(() -> sessions.find(NAME));
        safely(() -> usage.total(NAME));
        safely(() -> commands.poll(NAME));
        safely(subscribers::listPlans);
    }

    private static void safely(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.debug("warm-up step failed: {}", e.getMessage());
        }
    }
}
