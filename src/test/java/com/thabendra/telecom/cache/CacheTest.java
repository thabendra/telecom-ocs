package com.thabendra.telecom.cache;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.resilience.RedisBreaker;
import com.thabendra.telecom.subscriber.NotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CA-1 to CA-5 against real Oracle + Redis. The cache shares the test Redis here, so TestData.clean empties
 * it too. Skipped when Docker isn't running.
 */
@IntegrationTest
class CacheTest {

    private static final String MSISDN = "94771234567";
    private static final String PACKAGE = TestData.TEST_PACKAGE_PREFIX + "CACHE";
    private static final String TEST_KEY = "cache:v1:test";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ReadCache cache;

    @Autowired
    private CacheRedis cacheRedis;

    @Autowired
    private RedisBreaker dataBreaker;

    @Autowired
    private RedisMemoryMonitor memory;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    @Qualifier("redis")
    private GenericContainer<?> redisContainer;

    private int basicSpeed;
    private boolean paused;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        basicSpeed = jdbc.queryForObject("SELECT max_bandwidth_kbps FROM plan WHERE code = 'BASIC'", Integer.class);
        mvc.perform(post("/api/subscribers").with(TestUsers.careAgent()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":1000.00}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void restore() {
        if (paused) {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
        dataBreaker.reset();
        cacheRedis.breaker().reset();
        jdbc.update("UPDATE plan SET max_bandwidth_kbps = ?, updated_by = NULL, updated_at = NULL WHERE code = 'BASIC'",
                basicSpeed);
    }

    // CA-1, CA-3

    @Test
    void theSecondReadIsAHitAndTheValueHasAJitteredLife() throws Exception {
        double hits = reads("plans", "hit");

        mvc.perform(get("/api/plans")).andExpect(status().isOk());
        String second = mvc.perform(get("/api/plans")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(reads("plans", "hit")).isEqualTo(hits + 1);
        assertThat(second).contains("\"BASIC\"");
        assertThat(cacheRedis.template().getExpire(CacheKeys.PLANS, TimeUnit.SECONDS)).isBetween(7 * 60L, 12 * 60L);
    }

    @Test
    void aCachedAnswerIsTheSameAsAFreshOne() throws Exception {
        String fresh = mvc.perform(get("/api/packages")).andReturn().getResponse().getContentAsString();
        String cached = mvc.perform(get("/api/packages")).andReturn().getResponse().getContentAsString();

        assertThat(cached).isEqualTo(fresh);
    }

    // CA-2

    @Test
    void staffChangesToThePackagesShowAtOnce() throws Exception {
        createPackage();
        assertThat(packagePrice()).isEqualByComparingTo("99.00");

        mvc.perform(put("/api/admin/packages/{code}/price", PACKAGE).with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":150.00}"))
                .andExpect(status().isOk());
        assertThat(packagePrice()).isEqualByComparingTo("150.00");

        mvc.perform(put("/api/admin/packages/{code}/status", PACKAGE).with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/packages"))
                .andExpect(jsonPath("$[?(@.code == '%s')]".formatted(PACKAGE)).isEmpty());
    }

    @Test
    void aNewPlanSpeedReachesTheProfileThroughThePlanList() throws Exception {
        profile().andExpect(jsonPath("$.plan.maxBandwidthKbps").value(basicSpeed));

        mvc.perform(put("/api/admin/plans/{code}/speed", "BASIC").with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxBandwidthKbps\":7777}"))
                .andExpect(status().is2xxSuccessful());

        profile().andExpect(jsonPath("$.plan.maxBandwidthKbps").value(7777));
    }

    @Test
    void aSuspensionShowsInTheProfileAtOnceAndGoesAwayAgain() throws Exception {
        profile().andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(put("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"FRAUD\"}"))
                .andExpect(status().isOk());
        profile().andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(delete("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.careAgent()))
                .andExpect(status().isOk());
        profile().andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void theBalanceIsLiveEvenWhenTheProfileIsCached() throws Exception {
        profile().andExpect(jsonPath("$.balance").value(1000.00));
        double hits = reads("profile", "hit");

        jdbc.update("UPDATE subscriber SET balance = 400 WHERE msisdn = ?", MSISDN);   // as a charge would

        profile().andExpect(jsonPath("$.balance").value(400));
        assertThat(reads("profile", "hit")).isEqualTo(hits + 1);
    }

    @Test
    void aRollbackDeletesNothing() {
        cache.get("test", TEST_KEY, String.class, () -> "kept");

        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            cache.evictAfterCommit(TEST_KEY);
            transaction.setRollbackOnly();
        });

        assertThat(cacheRedis.template().opsForValue().get(TEST_KEY)).isNotNull();
    }

    @Test
    void theDelayedDeleteRemovesAnOldValueStoredJustAfterTheFirstDelete() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> cache.evictAfterCommit(TEST_KEY));
        assertThat(cacheRedis.template().hasKey(TEST_KEY)).isFalse();

        // a read that loaded the old row before the commit stores it now
        cacheRedis.template().opsForValue().set(TEST_KEY, "\"old\"");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(cacheRedis.template().hasKey(TEST_KEY)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(cacheRedis.template().hasKey(TEST_KEY)).isFalse();
    }

    // CA-4

    @Test
    void manyRequestsForAMissingKeyReloadItOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                answers.add(pool.submit(() -> {
                    go.await();
                    return cache.get("test", TEST_KEY, String.class, () -> {
                        loads.incrementAndGet();
                        sleep(50);
                        return "loaded";
                    });
                }));
            }
            go.countDown();
            for (Future<String> answer : answers) {
                assertThat(answer.get(10, TimeUnit.SECONDS)).isEqualTo("loaded");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void whenTheReloadTakesTooLongOthersReadOracleThemselvesAndStoreNothing() {
        cacheRedis.template().opsForValue().set("cache:lock:" + TEST_KEY, "another request");
        double gaveUp = reads("test", "wait-gave-up");

        assertThat(cache.get("test", TEST_KEY, String.class, () -> "from oracle")).isEqualTo("from oracle");

        assertThat(reads("test", "wait-gave-up")).isEqualTo(gaveUp + 1);
        assertThat(cacheRedis.template().hasKey(TEST_KEY)).isFalse();
    }

    @Test
    void aFailedReloadFreesTheLockAndStoresNothing() {
        assertThatThrownBy(() -> cache.get("test", TEST_KEY, String.class, () -> {
            throw new NotFoundException("nobody");
        })).isInstanceOf(NotFoundException.class);

        assertThat(cacheRedis.template().hasKey(TEST_KEY)).isFalse();
        assertThat(cache.get("test", TEST_KEY, String.class, () -> "second try")).isEqualTo("second try");
    }

    @Test
    void anUnknownSubscriberIs404EveryTimeAndNotCached() throws Exception {
        mvc.perform(get("/api/subscribers/{msisdn}", "94770000009").with(TestUsers.careAgent()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/subscribers/{msisdn}", "94770000009").with(TestUsers.careAgent()))
                .andExpect(status().isNotFound());

        assertThat(cacheRedis.template().hasKey(CacheKeys.profile("94770000009"))).isFalse();
    }

    @Test
    void aValueThatCannotBeReadIsLoadedAgain() {
        cacheRedis.template().opsForValue().set(TEST_KEY, "{not json");

        assertThat(cache.get("test", TEST_KEY, String.class, () -> "fresh")).isEqualTo("fresh");
        assertThat(cacheRedis.template().opsForValue().get(TEST_KEY)).isEqualTo("\"fresh\"");
    }

    // CA-5

    @Test
    void withRedisDownReadsComeFromOracleAndTheBreakerOpens() throws Exception {
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;

        for (int i = 0; i < 20 && cacheRedis.breaker().state() != CircuitBreaker.State.OPEN; i++) {
            mvc.perform(get("/api/plans")).andExpect(status().isOk());
        }

        assertThat(cacheRedis.breaker().state()).isEqualTo(CircuitBreaker.State.OPEN);
        long began = System.nanoTime();
        String plans = mvc.perform(get("/api/plans")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(plans).contains("\"BASIC\"");
        assertThat(reads("plans", "redis-down")).isPositive();
        assertThat((System.nanoTime() - began) / 1_000_000).as("no cache timeouts any more").isLessThan(2000);
    }

    @Test
    void aFailedDeleteDoesNotFailTheChange() {
        cacheRedis.breaker().reset();
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;

        new TransactionTemplate(transactions).executeWithoutResult(transaction -> cache.evictAfterCommit(TEST_KEY));
    }

    // RD-10

    @Test
    void theMemoryNumbersOfBothServersBecomeMetrics() {
        memory.check();

        for (String server : List.of("data", "cache")) {
            assertThat(meters.get("ocs.redis.memory.used.bytes").tag("server", server).gauge().value()).isPositive();
            assertThat(meters.get("ocs.redis.evicted.keys").tag("server", server).gauge().value()).isZero();
        }
    }

    private ResultActions profile() throws Exception {
        return mvc.perform(get("/api/subscribers/{msisdn}", MSISDN).with(TestUsers.customer(MSISDN)))
                .andExpect(status().isOk());
    }

    private void createPackage() throws Exception {
        mvc.perform(post("/api/admin/packages").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Cache test","price":99.00,"dataMb":3072,
                                 "validityDays":30,"maxBandwidthKbps":2048}""".formatted(PACKAGE)))
                .andExpect(status().isCreated());
    }

    private BigDecimal packagePrice() throws Exception {
        String body = mvc.perform(get("/api/packages")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> prices = JsonPath.read(body, "$[?(@.code == '%s')].price".formatted(PACKAGE));
        return new BigDecimal(prices.get(0).toString());
    }

    private double reads(String name, String result) {
        Counter counter = meters.find("ocs.cache.reads").tag("name", name).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
