package com.thabendra.telecom.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-3: which IP a per-IP bucket belongs to, and when X-Forwarded-For can be believed. */
class ClientAddressTest {

    private static final List<String> PROXIES = List.of("10.0.0.5", "10.0.0.6");

    @Test
    void withoutAProxyItIsTheConnectionsAddress() {
        assertThat(ClientAddress.of(request("203.0.113.7", null), PROXIES)).isEqualTo("203.0.113.7");
    }

    @Test
    void aHeaderFromAnyoneElseIsIgnored() {   // otherwise each request could pick a fresh bucket
        assertThat(ClientAddress.of(request("203.0.113.7", "1.2.3.4"), PROXIES)).isEqualTo("203.0.113.7");
    }

    @Test
    void behindOurProxyItIsTheAddressTheProxySaw() {
        assertThat(ClientAddress.of(request("10.0.0.5", "203.0.113.7"), PROXIES)).isEqualTo("203.0.113.7");
    }

    @Test
    void addressesTheCallerWroteThemselvesAreSkipped() {
        // the caller sent "X-Forwarded-For: 1.2.3.4"; our two proxies added what they saw
        assertThat(ClientAddress.of(request("10.0.0.6", "1.2.3.4, 203.0.113.7, 10.0.0.5"), PROXIES))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void onlyOurProxiesInTheHeaderMeansTheConnection() {
        assertThat(ClientAddress.of(request("10.0.0.5", "10.0.0.6"), PROXIES)).isEqualTo("10.0.0.5");
    }

    @Test
    void aProxyCanBeARangeAsAnIngressPodChangesAddress() {
        List<String> range = List.of("10.244.0.0/16");

        assertThat(ClientAddress.of(request("10.244.7.9", "203.0.113.7"), range)).isEqualTo("203.0.113.7");
        assertThat(ClientAddress.of(request("10.245.0.1", "203.0.113.7"), range)).isEqualTo("10.245.0.1");
        assertThat(ClientAddress.of(request("10.244.7.9", "203.0.113.7, 10.244.1.1"), range)).isEqualTo("203.0.113.7");
    }

    @Test
    void aHostNameInTheHeaderIsNeverLookedUp() {   // it isn't an address, so it is simply what the caller wrote
        assertThat(ClientAddress.of(request("10.244.7.9", "attacker.example"), List.of("10.244.0.0/16")))
                .isEqualTo("attacker.example");
    }

    private static MockHttpServletRequest request(String remote, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/plans");
        request.setRemoteAddr(remote);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
