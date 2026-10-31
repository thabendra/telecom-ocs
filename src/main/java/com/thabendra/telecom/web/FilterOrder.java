package com.thabendra.telecom.web;

import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.Ordered;

/**
 * The order every request passes the servlet filters in (lower runs first):
 * <pre>
 *   PortRuleFilter      right door for this API?              404
 *   Spring Security     who is calling? (certificate + token) 401 / 403
 *   RateLimitFilter     token left for this gateway?          429   (RL-1, RL-2)
 *   IdempotencyFilter   replay, or run once                   400 / 409 / 422
 *   ConcurrencyLimit    free database slot in this copy?      503   (RL-4)
 *   controller / SOAP endpoint -> Oracle
 * </pre>
 * Rate limiting needs the caller's verified name, so it comes after security; a refused request then
 * costs one Redis call and never reaches the idempotency store or the database.
 */
public final class FilterOrder {

    public static final int PORT_RULE = Ordered.HIGHEST_PRECEDENCE + 10;
    public static final int RATE_LIMIT = SecurityProperties.DEFAULT_FILTER_ORDER + 10;
    public static final int IDEMPOTENCY = SecurityProperties.DEFAULT_FILTER_ORDER + 20;
    public static final int CONCURRENCY_LIMIT = SecurityProperties.DEFAULT_FILTER_ORDER + 30;

    private FilterOrder() {
    }
}
