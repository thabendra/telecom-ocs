package com.thabendra.telecom.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collection;

/**
 * The caller's IP address for the per-IP bucket (PR-3).
 * <p>
 * Behind a load balancer every request seems to come from the balancer, which writes the real address
 * into X-Forwarded-For ("client, proxy1, proxy2"; each proxy adds the address it received from at the
 * end). The header is believed only when the request arrived from a trusted proxy, and then read from
 * the right: the first address that isn't one of our proxies is the caller. Addresses further left were
 * written by the caller itself, so they can be anything.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request, Collection<String> trustedProxies) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !trustedProxies.contains(remote)) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return remote;   // only our own proxies in the header
    }
}
