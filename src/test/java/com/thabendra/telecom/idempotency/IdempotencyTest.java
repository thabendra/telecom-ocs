package com.thabendra.telecom.idempotency;

import com.github.dockerjava.api.DockerClient;
import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.soap.WebServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section H against real Oracle + Redis: retries with the same Idempotency-Key are answered once,
 * misuse of a key is refused, two copies at the same moment run once, and the Oracle request-number
 * check still stops a double charge when Redis has lost the key or can't be reached (TE-3).
 * Skipped when Docker isn't running.
 */
@IntegrationTest
class IdempotencyTest {

    private static final String MSISDN = "94771234567";
    private static final String START_BODY = "{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(MSISDN);
    private static final String SOAP_START = """
            <s:StartSessionRequest>
              <s:msisdn>%s</s:msisdn><s:app>GENERAL</s:app><s:requestedMb>100</s:requestedMb>
            </s:StartSessionRequest>""".formatted(MSISDN);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    @Qualifier("redis")
    private GenericContainer<?> redisContainer;

    @LocalServerPort
    private int port;

    @BeforeEach
    void cleanUp() throws Exception {
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":100.00}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    @Test
    void missingKeyIsBadRequestAndNothingRuns() throws Exception {
        mvc.perform(post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(START_BODY)
                        .with(TestCerts.gateway(TestCerts.COLOMBO)))
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

        perform(updateRequest(key, "00000000-0000-0000-0000-000000000000", 1, 0))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void retryWhileFirstStillRunsIsConflictWithRetryAfter() throws Exception {
        String key = newKey();
        String hash = IdempotencyFilter.fingerprint("POST", "/charging/v1/sessions",
                START_BODY.getBytes(StandardCharsets.UTF_8));
        redis.opsForValue().set(IdempotencyStore.redisKey(TestCerts.COLOMBO, key),
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
    void keysAreSeparatePerGatewayCertificate() throws Exception {
        String key = newKey();

        String a = sessionIdOf(perform(startRequest(key)));                                        // pgw-colombo-01
        String b = sessionIdOf(perform(startRequest(key).with(TestCerts.gateway(TestCerts.KANDY))));

        assertThat(a).isNotEqualTo(b);
        assertThat(count("charging_session")).isEqualTo(2);
    }

    @Test
    void clientIdHeaderCannotBorrowAnotherGatewaysKeys() throws Exception {   // SE-1: the name comes from the cert
        String key = newKey();

        perform(startRequest(key).header(IdempotencyFilter.CLIENT_HEADER, TestCerts.KANDY))
                .andExpect(status().isCreated());

        assertThat(redis.hasKey(IdempotencyStore.redisKey(TestCerts.COLOMBO, key))).isTrue();
        assertThat(redis.hasKey(IdempotencyStore.redisKey(TestCerts.KANDY, key))).isFalse();
    }

    @Test
    void storedResultExpires() throws Exception {
        String key = newKey();
        perform(startRequest(key)).andExpect(status().isCreated());

        Long ttl = redis.getExpire(IdempotencyStore.redisKey(TestCerts.COLOMBO, key));
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

    @Test
    void twoCopiesSentAtTheSameMomentRunOnce() throws Exception {   // TE-3: concurrent retry
        String key = newKey();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            Callable<Integer> send = () -> {
                go.await();
                return mvc.perform(startRequest(key)).andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(send);
            Future<Integer> second = pool.submit(send);
            go.countDown();   // release both at once
            statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // one ran (201); the other saw the lock (409) or, if it came a moment later, the replay (201)
        assertThat(statuses).contains(201).allMatch(status -> status == 201 || status == 409);
        assertThat(count("charging_session")).isEqualTo(1);
        assertThat(subscriberColumn("plan_data_reserved_mb")).isEqualTo(100);
    }

    @Test
    void redisDownStillNoDoubleCharge() throws Exception {   // TE-3: Redis stopped, ID-8 + RD-6
        String session = startSession();
        perform(updateRequest(newKey(), session, 1, 60)).andExpect(status().isOk());

        // pause, not stop: the container keeps its port, so later tests reconnect to the same Redis
        DockerClient docker = redisContainer.getDockerClient();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            // Redis can't answer, so the filter lets the retry through; Oracle's request number stops it
            perform(updateRequest(newKey(), session, 1, 60))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("already processed")));
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(60);
    }

    // SOAP: real HTTPS calls to /ws on the gateway port, because MockMvc doesn't reach the SOAP servlet

    @Test
    void soapStartWithoutKeyIsClientFault() {
        ResponseEntity<String> answer = soap(null, SOAP_START);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(answer.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_XML)).isTrue();
        assertThat(answer.getBody()).contains("<faultcode>SOAP-ENV:Client</faultcode>", "Idempotency-Key");
        assertThat(count("charging_session")).isZero();
    }

    @Test
    void soapStartRetryIsReplayedAndRunsOnce() {
        String key = newKey();

        ResponseEntity<String> first = soap(key, SOAP_START);
        ResponseEntity<String> second = soap(key, SOAP_START);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());              // same sessionId, same grant
        assertThat(count("charging_session")).isEqualTo(1);                  // the retry didn't open a 2nd session
        assertThat(subscriberColumn("plan_data_reserved_mb")).isEqualTo(100);
    }

    @Test
    void soapReadsDoNotNeedAKey() {
        ResponseEntity<String> answer = soap(null, "<s:ListPlansRequest/>");

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).contains("ListPlansResponse");
    }

    @Test
    void soapSameKeyForAnotherOperationIsUnprocessable() {
        String key = newKey();
        assertThat(soap(key, SOAP_START).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> answer = soap(key, """
                <s:ReleaseSessionRequest>
                  <s:sessionId>00000000-0000-0000-0000-000000000000</s:sessionId>
                  <s:requestNumber>1</s:requestNumber><s:usedMb>0</s:usedMb>
                </s:ReleaseSessionRequest>""");

        assertThat(answer.getStatusCode().value()).isEqualTo(422);
        assertThat(answer.getBody()).contains("SOAP-ENV:Client", "different request");
    }

    @Test
    void soapRetryWhileFirstStillRunsIsConflictWithRetryAfter() {
        String key = newKey();
        String hash = IdempotencyFilter.fingerprint("POST", "/ws",
                soapEnvelope(SOAP_START).getBytes(StandardCharsets.UTF_8));
        redis.opsForValue().set(IdempotencyStore.redisKey(TestCerts.COLOMBO, key),
                "{\"state\":\"IN_PROGRESS\",\"requestHash\":\"" + hash + "\"}", Duration.ofSeconds(30));

        ResponseEntity<String> answer = soap(key, SOAP_START);

        assertThat(answer.getStatusCode().value()).isEqualTo(409);
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(answer.getBody()).contains("SOAP-ENV:Client", "still being processed");
        assertThat(count("charging_session")).isZero();
    }

    // POST /ws with pgw-colombo-01's certificate, without throwing on 4xx/5xx, so the tests can look at faults
    private ResponseEntity<String> soap(String key, String payload) {
        return TestCerts.exchange(TestCerts.https(port, TestCerts.COLOMBO).post()
                .uri("/ws")
                .contentType(MediaType.TEXT_XML)
                .headers(headers -> {
                    if (key != null) {
                        headers.set(IdempotencyFilter.KEY_HEADER, key);
                    }
                })
                .body(soapEnvelope(payload)));
    }

    private static String soapEnvelope(String payload) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:s="%s">
                  <soapenv:Body>%s</soapenv:Body>
                </soapenv:Envelope>""".formatted(WebServiceConfig.NAMESPACE, payload);
    }

    private String startSession() throws Exception {
        return sessionIdOf(perform(startRequest(newKey())).andExpect(status().isCreated()));
    }

    // charging calls come from pgw-colombo-01's certificate unless a test adds another one after it
    private MockHttpServletRequestBuilder startRequest(String key) {
        return post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .with(TestCerts.gateway(TestCerts.COLOMBO))
                .header(IdempotencyFilter.KEY_HEADER, key).content(START_BODY);
    }

    private MockHttpServletRequestBuilder updateRequest(String key, String sessionId, long requestNumber, long usedMb) {
        return post("/charging/v1/sessions/{id}/update", sessionId).contentType(MediaType.APPLICATION_JSON)
                .with(TestCerts.gateway(TestCerts.COLOMBO))
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
