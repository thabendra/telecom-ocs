package com.thabendra.telecom.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * The caller's IP address for the per-IP bucket (PR-3).
 * <p>
 * Behind a load balancer every request seems to come from the balancer, which writes the real address
 * into X-Forwarded-For ("client, proxy1, proxy2"; each proxy adds the address it received from at the
 * end). The header is believed only when the request arrived from a trusted proxy, and then read from
 * the right: the first address that isn't one of our proxies is the caller. Addresses further left were
 * written by the caller itself, so they can be anything.
 * <p>
 * A trusted proxy is an address or a range (10.244.0.0/16): an ingress controller's pods change address.
 */
public final class ClientAddress {

    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request, Collection<String> trustedProxies) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrusted(remote, trustedProxies)) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrusted(hop, trustedProxies)) {
                return hop;
            }
        }
        return remote;   // only our own proxies in the header
    }

    private static boolean isTrusted(String address, Collection<String> trustedProxies) {
        if (trustedProxies.contains(address)) {
            return true;
        }
        // the header can hold anything, and the matcher would look a host name up in DNS: only real addresses
        return IP_ADDRESS.matcher(address).matches() && trustedProxies.stream()
                .filter(proxy -> proxy.contains("/"))
                .anyMatch(range -> new IpAddressMatcher(range).matches(address));
    }
}
