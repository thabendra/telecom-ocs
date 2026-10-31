package com.thabendra.telecom.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section H against real Oracle + Redis: retries with the same Idempotency-Key are answered once,
 * misuse of a key is refused, and the Oracle request-number check still stops a double charge
 * when Redis has lost the key. Skipped when Docker isn't running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyTest {

    private static final String MSISDN = "94771234567";
    private static final String START_BODY = "{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(MSISDN);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private IdempotencyStore store;

    @BeforeEach
    void cleanUp() throws Exception {
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":100.00}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    @Test
    void missingKeyIsBadRequestAndNothingRuns() throws Exception {
        mvc.perform(post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(START_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));

        assertThat(count("charging_session")).isZero();
    }

    @Test
    void malformedKeyIsBadRequest() throws Exception {
        perform(startRequest("has spaces and : colons")).andExpect(status().isBadRequest());
    }

    @Test
    void readsDoNotNeedAKey() throws Exception {
        mvc.perform(get("/api/plans")).andExpect(status().isOk());
    }

    @Test
    void retryWithSameKeyReplaysFirstAnswerAndRunsOnce() throws Exception {
        String key = newKey();

        String first = perform(startRequest(key))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();

        String second = perform(startRequest(key))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);                                   // same sessionId, same grant
        assertThat(count("charging_session")).isEqualTo(1);                    // only one session created
        assertThat(subscriberColumn("plan_data_reserved_mb")).isEqualTo(100);  // reserved once
    }

    @Test
    void retriedUpdateChargesOnce() throws Exception {
        String session = startSession();
        String key = newKey();

        perform(updateRequest(key, session, 1, 60)).andExpect(status().isOk());
        perform(updateRequest(key, session, 1, 60))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(60);
    }

    @Test
    void sameKeyWithDifferentBodyIsUnprocessable() throws Exception {
        String session = startSession();
        String key = newKey();
        perform(updateRequest(key, session, 1, 60)).andExpect(status().isOk());

        perform(updateRequest(key, session, 1, 99)).andExpect(status().isUnprocessableEntity());
        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(60);
    }

    @Test
    void sameKeyOnAnotherEndpointIsUnprocessable() throws Exception {
        String key = newKey();
        perform(startRequest(key)).andExpect(status().isCreated());

        perform(post("/api/subscribers/{msisdn}/top-ups", MSISDN).contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.KEY_HEADER, key).content("{\"amount\":50.00}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void retryWhileFirstStillRunsIsConflictWithRetryAfter() throws Exception {
        String key = newKey();
        String hash = IdempotencyFilter.fingerprint("POST", "/charging/v1/sessions",
                START_BODY.getBytes(StandardCharsets.UTF_8));
        redis.opsForValue().set(IdempotencyStore.redisKey("anonymous", key),
                "{\"state\":\"IN_PROGRESS\",\"requestHash\":\"" + hash + "\"}", Duration.ofSeconds(30));

        perform(startRequest(key))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
        assertThat(count("charging_session")).isZero();
    }

    @Test
    void inProgressLockIsShortLivedAndSeenInOneCall() {
        String redisKey = IdempotencyStore.redisKey("pgw-colombo-01", newKey());

        assertThat(store.claimOrFind(redisKey, "hash-1")).isEmpty();             // miss: lock taken
        assertThat(redis.getExpire(redisKey)).isBetween(1L, 5L);                  // 5s lock TTL, not 30s

        assertThat(store.claimOrFind(redisKey, "hash-1"))                         // hit: same call returns the lock
                .hasValueSatisfying(record -> assertThat(record.state()).isEqualTo(IdempotencyRecord.State.IN_PROGRESS));
    }

    @Test
    void clientErrorIsStoredAndReplayed() throws Exception {
        String key = newKey();
        String unknown = "00000000-0000-0000-0000-000000000000";

        perform(updateRequest(key, unknown, 1, 0)).andExpect(status().isNotFound());
        perform(updateRequest(key, unknown, 1, 0))
                .andExpect(status().isNotFound())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
    }

    @Test
    void keysAreSeparatePerClient() throws Exception {
        String key = newKey();

        String a = sessionIdOf(perform(startRequest(key).header(IdempotencyFilter.CLIENT_HEADER, "pgw-colombo-01")));
        String b = sessionIdOf(perform(startRequest(key).header(IdempotencyFilter.CLIENT_HEADER, "pgw-kandy-01")));

        assertThat(a).isNotEqualTo(b);
        assertThat(count("charging_session")).isEqualTo(2);
    }

    @Test
    void storedResultExpires() throws Exception {
        String key = newKey();
        perform(startRequest(key).header(IdempotencyFilter.CLIENT_HEADER, "pgw-colombo-01"))
                .andExpect(status().isCreated());

        Long ttl = redis.getExpire(IdempotencyStore.redisKey("pgw-colombo-01", key));
        assertThat(ttl).isBetween(1L, Duration.ofHours(24).toSeconds());
    }

    @Test
    void oracleStopsDoubleChargeWhenRedisLostTheKey() throws Exception {
        String session = startSession();
        perform(updateRequest(newKey(), session, 1, 60)).andExpect(status().isOk());

        TestData.flushRedis(redis);   // Redis forgets every key; the database keeps its data
        perform(updateRequest(newKey(), session, 1, 60))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already processed")));

        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(60);
    }

    private String startSession() throws Exception {
        return sessionIdOf(perform(startRequest(newKey())).andExpect(status().isCreated()));
    }

    private MockHttpServletRequestBuilder startRequest(String key) {
        return post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.KEY_HEADER, key).content(START_BODY);
    }

    private MockHttpServletRequestBuilder updateRequest(String key, String sessionId, long requestNumber, long usedMb) {
        return post("/charging/v1/sessions/{id}/update", sessionId).contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.KEY_HEADER, key)
                .content("{\"requestNumber\":%d,\"usedMb\":%d,\"requestedMb\":100}".formatted(requestNumber, usedMb));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private static String sessionIdOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.sessionId");
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long subscriberColumn(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM subscriber WHERE msisdn = ?", Long.class, MSISDN);
    }
}
