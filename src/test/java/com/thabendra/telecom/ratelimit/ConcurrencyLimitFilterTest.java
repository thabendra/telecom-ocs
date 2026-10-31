package com.thabendra.telecom.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** The database bulkhead (RL-4), with one slot so a second request at the same moment has to wait. */
class ConcurrencyLimitFilterTest {

    private final ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(
            new RateLimitProperties(10, 10, 1, Map.of(), 1, Duration.ofMillis(50)), new ObjectMapper());

    @Test
    void secondRequestWhileTheSlotIsTakenGets503AndTheSlotComesBack() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                filter.doFilter(request("/charging/v1/sessions"), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    await(finish);
                });
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();   // the first request holds the only slot

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("/charging/v1/sessions"), second, (req, res) -> fail("must not run"));

        assertThat(second.getStatus()).isEqualTo(503);
        assertThat(second.getHeader("Retry-After")).isEqualTo("1");
        assertThat(second.getContentAsString()).contains("in progress");

        finish.countDown();
        first.join(5000);
        assertThat(failure.get()).isNull();
        assertThat(filter.freeSlots()).isEqualTo(1);
    }

    @Test
    void slotIsGivenBackWhenTheRequestFails() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("database down");
        };

        assertThatThrownBy(() -> filter.doFilter(request("/api/plans"), new MockHttpServletResponse(), failing))
                .hasMessage("database down");
        assertThat(filter.freeSlots()).isEqualTo(1);
    }

    @Test
    void soapCallerGetsASoapFault() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                filter.doFilter(request("/ws"), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    await(finish);
                });
            } catch (Exception ignored) {
                // checked by the slot count below
            }
        });
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("/ws"), second, (req, res) -> fail("must not run"));

        assertThat(second.getStatus()).isEqualTo(503);
        assertThat(second.getContentType()).startsWith("text/xml");
        assertThat(second.getContentAsString()).contains("SOAP-ENV:Fault");

        finish.countDown();
        first.join(5000);
        assertThat(filter.freeSlots()).isEqualTo(1);
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
