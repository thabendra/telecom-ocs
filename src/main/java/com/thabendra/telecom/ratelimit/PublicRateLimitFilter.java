package com.thabendra.telecom.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thabendra.telecom.customer.CustomerTokens;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limits the public API (PR-3) with the same token buckets as the gateways (group 4):
 * <pre>
 *   logged-in customer   user:{msisdn}   telecom.public-rate-limit.customer
 *   logged-in staff      staff:{login}   telecom.public-rate-limit.staff
 *   nobody logged in     ip:{address}    telecom.public-rate-limit.anonymous   (the OTP login, the catalog)
 * </pre>
 * A logged-in caller is counted by who they are, not by IP: many phones share one IP behind the mobile
 * network's NAT (CGNAT), and one person can't escape their limit by changing networks.
 * The per-IP bucket is what slows down guessing login codes and SMS flooding.
 */
@Component
@Order(FilterOrder.RATE_LIMIT)
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private final GatewayRateLimiter limiter;
    private final PublicRateLimitProperties limits;
    private final ObjectMapper json;
    private final OcsMetrics metrics;

    public PublicRateLimitFilter(GatewayRateLimiter limiter, PublicRateLimitProperties limits, ObjectMapper json,
                                 OcsMetrics metrics) {
        this.limiter = limiter;
        this.limits = limits;
        this.json = json;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        Decision decision;
        String kind;
        if (isLoggedIn(caller) && has(caller, CustomerTokens.ROLE)) {
            kind = "customer";
            decision = limiter.tryAcquire("user:" + caller.getName(), limits.customer());
        } else if (isLoggedIn(caller)) {
            kind = "staff";
            decision = limiter.tryAcquire("staff:" + caller.getName(), limits.staff());
        } else {
            kind = "ip";
            decision = limiter.tryAcquire("ip:" + ClientAddress.of(request, limits.trustedProxies()), limits.anonymous());
        }
        if (!decision.allowed()) {
            metrics.rateLimited("public", kind);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            ErrorResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests, retry after the Retry-After seconds", false, json);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isLoggedIn(Authentication caller) {
        return caller != null && caller.isAuthenticated() && !(caller instanceof AnonymousAuthenticationToken);
    }

    private static boolean has(Authentication caller, String authority) {
        return caller.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }
}
