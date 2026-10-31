package com.thabendra.telecom.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * telecom.public-rate-limit.* in application.yml: the public API's buckets (PR-3).
 *
 * @param anonymous      per IP address, for callers who aren't logged in (the OTP login, the catalog)
 * @param customer       per logged-in customer number
 * @param staff          per staff login
 * @param trustedProxies addresses of our own load balancers / proxies. Only a request coming from one of
 *                       them has its X-Forwarded-For header believed; anyone else could write any IP there
 */
@ConfigurationProperties("telecom.public-rate-limit")
public record PublicRateLimitProperties(RateLimitProperties.Limit anonymous, RateLimitProperties.Limit customer,
                                        RateLimitProperties.Limit staff, List<String> trustedProxies) {

    public List<String> trustedProxies() {
        return trustedProxies == null ? List.of() : trustedProxies;
    }
}
