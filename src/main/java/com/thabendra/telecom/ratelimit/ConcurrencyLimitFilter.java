package com.thabendra.telecom.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bulkhead in front of the database (RL-4): at most maxInFlight requests of this backend copy run at
 * the same moment, the size of its connection pool. When Oracle is slow, requests don't pile up waiting
 * for a connection and use up every thread; a request that finds no free slot within inFlightWait gets
 * 503 + Retry-After at once. The gateway retries it, and opens its circuit breaker if it keeps happening.
 * Local to this copy on purpose: each copy protects its own pool. Across copies the rule is
 * pool size x most copies <= sessions the database can take.
 */
@Component
@Order(FilterOrder.CONCURRENCY_LIMIT)
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private final Semaphore slots;
    private final long waitMs;
    private final ObjectMapper json;

    public ConcurrencyLimitFilter(RateLimitProperties properties, ObjectMapper json) {
        this.slots = new Semaphore(properties.maxInFlight(), true);   // fair: first come, first served
        this.waitMs = properties.inFlightWait().toMillis();
        this.json = json;
    }

    /** Slots free right now (for tests and metrics). */
    public int freeSlots() {
        return slots.availablePermits();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            ErrorResponses.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many requests in progress, retry after the Retry-After seconds",
                    request.getRequestURI().startsWith(request.getContextPath() + "/ws"), json);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();   // always, also when the request fails
        }
    }
}
