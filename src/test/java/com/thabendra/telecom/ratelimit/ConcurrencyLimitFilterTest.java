package com.thabendra.telecom.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thabendra.telecom.idempotency.CachedBodyRequest;
import com.thabendra.telecom.monitoring.OcsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** The database bulkhead (RL-4), with one slot so a second request at the same moment has to wait; load shedding (RL-5). */
class ConcurrencyLimitFilterTest {

    private final ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(
            new RateLimitProperties(10, 10, 1, Map.of(), 1, Duration.ofMillis(50), 0, Duration.ofMillis(50)), new ObjectMapper(),
            new OcsMetrics(new SimpleMeterRegistry()));

    // RL-5: 3 slots, 1 of them only for update and release
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ConcurrencyLimitFilter shedding = new ConcurrencyLimitFilter(
            new RateLimitProperties(10, 10, 1, Map.of(), 3, Duration.ofMillis(50), 1, Duration.ofMillis(800)), new ObjectMapper(),
            new OcsMetrics(meters));
    private final CountDownLatch finish = new CountDownLatch(1);

    @Test
    void whenOverloadedOtherWorkIsRefusedAndUpdateAndReleaseStillRun() throws Exception {
        Thread one = hold("/charging/v1/sessions");
        Thread two = hold("/charging/v1/sessions");

        assertRefused("/charging/v1/sessions");
        assertRefused("/api/plans");
        assertRefused(soap("StartSessionRequest"));
        assertThat(meters.counter("ocs.load.shed").count()).isEqualTo(3);
        assertThat(meters.find("ocs.bulkhead.rejected").counter()).isNull();

        assertRuns(request("/charging/v1/sessions/abc/update"));
        assertRuns(request("/charging/v1/sessions/abc/release"));
        assertRuns(soap("UpdateSessionRequest"));
        assertRuns(soap("ReleaseSessionRequest"));

        release(one, two);
        assertThat(shedding.freeSlots()).isEqualTo(3);
    }

    @Test
    void newSessionsRunAgainOnceTheLoadHasGone() throws Exception {
        release(hold("/charging/v1/sessions"), hold("/charging/v1/sessions"));

        assertRuns(request("/charging/v1/sessions"));
    }

    @Test
    void withEverySlotTakenEvenAnUpdateGets503() throws Exception {
        Thread one = hold("/charging/v1/sessions");
        Thread two = hold("/charging/v1/sessions");
        Thread three = hold("/charging/v1/sessions/abc/update");

        assertRefused("/charging/v1/sessions/abc/release");
        assertThat(meters.counter("ocs.bulkhead.rejected").count()).isEqualTo(1);

        release(one, two, three);
    }

    @Test
    void anUpdateMayUseAnySlotWhenThereIsLittleTraffic() throws Exception {
        Thread one = hold("/charging/v1/sessions/a/update");
        Thread two = hold("/charging/v1/sessions/b/update");

        assertRuns(request("/charging/v1/sessions"));   // the two updates took no slot from other work

        release(one, two);
    }

    @Test
    void whileAnUpdateWaitsNoNewWorkStartsAndThenItGetsTheSlot() throws Exception {
        Thread one = hold("/charging/v1/sessions");   // one slot of other work is still free ...
        Thread two = hold("/charging/v1/sessions/a/update");
        Thread three = hold("/charging/v1/sessions/b/update");   // ... but every slot is taken
        AtomicBoolean ran = new AtomicBoolean();
        Thread waiting = new Thread(() -> {
            try {
                shedding.doFilter(request("/charging/v1/sessions/c/update"), new MockHttpServletResponse(),
                        (req, res) -> ran.set(true));
            } catch (Exception ignored) {
                // checked by ran
            }
        });
        waiting.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (shedding.ongoingWaiting() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }

        assertRefused("/charging/v1/sessions");
        assertThat(meters.counter("ocs.load.shed").count()).isEqualTo(1);

        release(one, two, three);
        waiting.join(5000);
        assertThat(ran).isTrue();
        assertThat(shedding.freeSlots()).isEqualTo(3);
    }

    // a request that stays inside the filter until finish
    private Thread hold(String path) throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                shedding.doFilter(request(path), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    await(finish);
                });
            } catch (Exception ignored) {
                // checked by the slot counts
            }
        });
        thread.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        return thread;
    }

    private void release(Thread... threads) throws InterruptedException {
        finish.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }
    }

    private void assertRefused(String path) throws Exception {
        assertRefused(request(path));
    }

    private void assertRefused(jakarta.servlet.http.HttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        shedding.doFilter(request, response, (req, res) -> fail("must not run"));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    private void assertRuns(jakarta.servlet.http.HttpServletRequest request) throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        shedding.doFilter(request, new MockHttpServletResponse(), (req, res) -> ran.set(true));
        assertThat(ran).isTrue();
    }

    // the way IdempotencyFilter passes a SOAP call on
    private static CachedBodyRequest soap(String operation) throws Exception {
        MockHttpServletRequest request = request("/ws");
        request.setContent("""
                <e:Envelope xmlns:e="http://schemas.xmlsoap.org/soap/envelope/">
                  <e:Body><s:%s xmlns:s="http://thabendra.com/telecom/subscriber"/></e:Body>
                </e:Envelope>""".formatted(operation).getBytes(StandardCharsets.UTF_8));
        return new CachedBodyRequest(request);
    }

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
