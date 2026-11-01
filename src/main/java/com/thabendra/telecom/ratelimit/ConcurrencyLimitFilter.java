package com.thabendra.telecom.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thabendra.telecom.idempotency.CachedBodyRequest;
import com.thabendra.telecom.monitoring.OcsMetrics;
import com.thabendra.telecom.web.ErrorResponses;
import com.thabendra.telecom.web.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bulkhead in front of the database (RL-4): at most maxInFlight requests of this backend copy run at
 * the same moment, the size of its connection pool. When Oracle is slow, requests don't pile up waiting
 * for a connection and use up every thread; a request that finds no free slot within inFlightWait gets
 * 503 + Retry-After at once. The gateway retries it. Local to this copy on purpose: each copy protects
 * its own pool. Across copies the rule is pool size x most copies <= sessions the database can take.
 * <p>
 * Load shedding (RL-5): update and release report usage that has to be charged and free reserved balance,
 * while a refused new session only tries again. So reservedSlots slots are kept for those two: everything
 * else (new sessions first of all) is refused at once when it would need one of them. The slots aren't
 * set aside for good: with little traffic an update simply takes any free slot. Keeping slots isn't enough when
 * Oracle is saturated, as they then clear slowly: so an update may wait longer, and while one waits no other
 * work starts, which also takes load off Oracle.
 */
@Component
@Order(FilterOrder.CONCURRENCY_LIMIT)
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private static final Set<String> ONGOING_SESSION_PATHS = Set.of("/charging/v1/sessions/*/update",
            "/charging/v1/sessions/*/release");
    private static final Set<String> ONGOING_SESSION_OPERATIONS = Set.of("UpdateSessionRequest",
            "ReleaseSessionRequest");

    private final Semaphore slots;
    private final Semaphore otherWork;
    private final long waitMs;
    private final long ongoingWaitMs;
    private final AtomicInteger ongoingWaiting = new AtomicInteger();
    private final ObjectMapper json;
    private final OcsMetrics metrics;
    private final AntPathMatcher paths = new AntPathMatcher();

    public ConcurrencyLimitFilter(RateLimitProperties properties, ObjectMapper json, OcsMetrics metrics) {
        this.slots = new Semaphore(properties.maxInFlight(), true);   // fair: first come, first served
        this.otherWork = new Semaphore(Math.max(1, properties.maxInFlight() - properties.reservedSlots()));
        this.waitMs = properties.inFlightWait().toMillis();
        this.ongoingWaitMs = properties.ongoingWait().toMillis();
        this.json = json;
        this.metrics = metrics;
        metrics.bulkheadSlots(slots::availablePermits);
    }

    /** Update and release calls waiting for a slot right now (for tests). */
    int ongoingWaiting() {
        return ongoingWaiting.get();
    }

    /** Slots free right now (for tests and metrics). */
    public int freeSlots() {
        return slots.availablePermits();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean ongoing = isOngoingSession(request);
        if (!ongoing && (ongoingWaiting.get() > 0 || !otherWork.tryAcquire())) {
            metrics.loadShed();
            refuse(request, response);
            return;
        }
        try {
            runInASlot(ongoing, request, response, chain);
        } finally {
            if (!ongoing) {
                otherWork.release();
            }
        }
    }

    private void runInASlot(boolean ongoing, HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
        if (!(ongoing ? waitAsOngoing() : freeSlot(waitMs))) {
            metrics.bulkheadRejected();
            refuse(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();   // always, also when the request fails
        }
    }

    private boolean waitAsOngoing() {
        ongoingWaiting.incrementAndGet();
        try {
            return freeSlot(ongoingWaitMs);
        } finally {
            ongoingWaiting.decrementAndGet();
        }
    }

    private boolean freeSlot(long wait) {
        try {
            return slots.tryAcquire(wait, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // SOAP: every call goes to /ws, so the operation is in the body (IdempotencyFilter has already read it)
    private boolean isOngoingSession(HttpServletRequest request) {
        if (request instanceof CachedBodyRequest cached && isSoap(request)) {
            return ONGOING_SESSION_OPERATIONS.contains(cached.soapOperation());
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ONGOING_SESSION_PATHS.stream().anyMatch(pattern -> paths.match(pattern, path));
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        ErrorResponses.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                "Too many requests in progress, retry after the Retry-After seconds", isSoap(request), json);
    }

    private static boolean isSoap(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/ws");
    }
}
