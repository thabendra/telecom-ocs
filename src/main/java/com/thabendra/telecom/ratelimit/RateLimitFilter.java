package com.thabendra.telecom.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thabendra.telecom.gateway.GatewayProperties;
import com.thabendra.telecom.web.ErrorResponses;
import com.thabendra.telecom.web.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limits the gateway API per gateway (RL-1, RL-2): one token per request from that gateway's
 * bucket; an empty bucket gets 429 + Retry-After, before idempotency or the database are touched.
 * The gateway's retry policy already waits Retry-After and tries again.
 * Every request counts, retries too, so a retry storm is limited as well.
 */
@Component
@Order(FilterOrder.RATE_LIMIT)
public class RateLimitFilter extends OncePerRequestFilter {

    private final GatewayRateLimiter limiter;
    private final GatewayProperties gateways;
    private final ObjectMapper json;
    private final AntPathMatcher paths = new AntPathMatcher();

    public RateLimitFilter(GatewayRateLimiter limiter, GatewayProperties gateways, ObjectMapper json) {
        this.limiter = limiter;
        this.gateways = gateways;
        this.json = json;
    }

    // only the gateway API; the public API has its own buckets per user / IP (PublicRateLimitFilter, PR-3)
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return gateways.paths().stream().noneMatch(pattern -> paths.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String gateway = verifiedGateway();
        if (gateway == null) {
            chain.doFilter(request, response);   // not authenticated: security has already answered
            return;
        }
        Decision decision = limiter.tryAcquire(gateway);
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            ErrorResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit for " + gateway + " exceeded, retry after the Retry-After seconds",
                    pathOf(request).startsWith("/ws"), json);
            return;
        }
        chain.doFilter(request, response);
    }

    // the name from the certificate + token (group 3), so a gateway can't use another one's bucket
    private static String verifiedGateway() {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller == null || !caller.isAuthenticated() || caller instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return caller.getName();
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
