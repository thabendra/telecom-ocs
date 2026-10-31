package com.thabendra.telecom.customer;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestSms;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-1 and CU-2 against Redis + Oracle with real customer tokens: the SMS code login, the token only
 * opening the customer's own number, the guessing limits, and the customer's own features (quota,
 * top-up, package, usage history). Skipped when Docker isn't running.
 */
@IntegrationTest
class CustomerLoginTest {

    private static final String KAMAL = "94771234567";
    private static final String NIMAL = "94777654321";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TestSms sms;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        sms.clear();
        createSubscriber(KAMAL, "1000.00");
        createSubscriber(NIMAL, "100.00");
    }

    // CU-1: the login

    @Test
    void theCodeFromTheSmsGivesATokenForTheOwnNumber() throws Exception {
        String token = login(KAMAL);

        mvc.perform(get("/api/subscribers/{msisdn}/quota", KAMAL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msisdn").value(KAMAL));
    }

    @Test
    void theTokenAnswerIsNeverCached() throws Exception {
        requestCode(KAMAL).andExpect(status().isAccepted());

        verify(KAMAL, sms.lastCode(KAMAL))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void aCustomerCannotOpenAnotherNumber() throws Exception {   // no IDOR
        String kamals = login(KAMAL);

        mvc.perform(get("/api/subscribers/{msisdn}/quota", NIMAL).header(HttpHeaders.AUTHORIZATION, bearer(kamals)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/subscribers/{msisdn}/top-ups", NIMAL).header(HttpHeaders.AUTHORIZATION, bearer(kamals))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerCannotUseStaffApis() throws Exception {
        String token = login(KAMAL);

        mvc.perform(put("/api/subscribers/{msisdn}/suspension", KAMAL).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/packages").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutATokenOnlyTheLoginAndTheCatalogAreOpen() throws Exception {
        mvc.perform(get("/api/subscribers/{msisdn}/quota", KAMAL)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/packages")).andExpect(status().isOk());
        mvc.perform(get("/api/plans")).andExpect(status().isOk());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        CustomerTokens forger = new CustomerTokens(new CustomerProperties(null, new CustomerProperties.Token(
                "telecom-ocs-customer-login", "customer-api", "a-different-secret-that-is-32-bytes-long",
                Duration.ofMinutes(15))));
        String forged = forger.issue(NIMAL).accessToken();

        mvc.perform(get("/api/subscribers/{msisdn}/quota", NIMAL).header(HttpHeaders.AUTHORIZATION, bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        requestCode(KAMAL).andExpect(status().isAccepted());
        String code = sms.lastCode(KAMAL);

        verify(KAMAL, code).andExpect(status().isOk());
        verify(KAMAL, code).andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongCodesDeleteTheCode() throws Exception {
        requestCode(KAMAL).andExpect(status().isAccepted());
        String code = sms.lastCode(KAMAL);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            verify(KAMAL, wrong).andExpect(status().isUnauthorized());
        }
        verify(KAMAL, code).andExpect(status().isUnauthorized());   // even the right code is gone now
        assertThat(redis.hasKey(OtpService.codeKey(KAMAL))).isFalse();
    }

    @Test
    void redisKeepsOnlyAHashOfTheCode() throws Exception {
        requestCode(KAMAL).andExpect(status().isAccepted());

        Object stored = redis.opsForHash().get(OtpService.codeKey(KAMAL), "hash");
        assertThat(stored).isNotNull().isNotEqualTo(sms.lastCode(KAMAL));
        assertThat(redis.getExpire(OtpService.codeKey(KAMAL))).isBetween(1L, 300L);
    }

    @Test
    void aNewCodeOnlyOncePerMinute() throws Exception {
        requestCode(KAMAL).andExpect(status().isAccepted());

        requestCode(KAMAL)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void anUnknownNumberGetsTheSameAnswerButNoSms() throws Exception {
        requestCode("94770000000").andExpect(status().isAccepted());

        assertThat(sms.lastCode("94770000000")).isNull();
        verify("94770000000", "123456").andExpect(status().isUnauthorized());
    }

    @Test
    void aBadNumberOrCodeIs400() throws Exception {
        requestCode("0771234567").andExpect(status().isBadRequest());
        verify(KAMAL, "12ab").andExpect(status().isBadRequest());
    }

    // CU-2: the customer's own features, with a real customer token

    @Test
    void theCustomerTopsUpBuysAPackageAndSeesUsage() throws Exception {
        String token = login(KAMAL);

        mvc.perform(post("/api/subscribers/{msisdn}/top-ups", KAMAL).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1100.0));

        mvc.perform(post("/api/subscribers/{msisdn}/packages", KAMAL).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packageCode\":\"WA_2GB\"}"))
                .andExpect(status().isCreated());

        useData(KAMAL, 50);   // a gateway session: start, then release after 50 MB
        mvc.perform(get("/api/subscribers/{msisdn}/usage", KAMAL).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dataMb").value(50))
                .andExpect(jsonPath("$.items[0].source").value("PLAN"));
    }

    @Test
    void usageHistoryIsPagedAndChecksTheDates() throws Exception {
        String token = login(KAMAL);
        useData(KAMAL, 10);
        useData(KAMAL, 20);

        mvc.perform(get("/api/subscribers/{msisdn}/usage?size=1&page=0", KAMAL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dataMb").value(20));   // newest first
        mvc.perform(get("/api/subscribers/{msisdn}/usage?size=1&page=1", KAMAL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.items[0].dataMb").value(10));
        mvc.perform(get("/api/subscribers/{msisdn}/usage?from=2026-10-02T00:00:00&to=2026-10-01T00:00:00", KAMAL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/subscribers/{msisdn}/usage?size=500", KAMAL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest());
    }

    private String login(String msisdn) throws Exception {
        requestCode(msisdn).andExpect(status().isAccepted());
        String body = verify(msisdn, sms.lastCode(msisdn)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions requestCode(String msisdn) throws Exception {
        return mvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"%s\"}".formatted(msisdn)));
    }

    private ResultActions verify(String msisdn, String code) throws Exception {
        return mvc.perform(post("/api/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"%s\",\"code\":\"%s\"}".formatted(msisdn, code)));
    }

    private void createSubscriber(String msisdn, String balance) throws Exception {
        mvc.perform(post("/api/subscribers").with(TestUsers.careAgent()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":%s}""".formatted(msisdn, balance)))
                .andExpect(status().isCreated());
    }

    // general data from a gateway (group 1): one session, released with what was used
    private void useData(String msisdn, long usedMb) throws Exception {
        String started = mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(TestCerts.COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(msisdn)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String session = JsonPath.read(started, "$.sessionId");
        mvc.perform(post("/charging/v1/sessions/{id}/release", session).with(TestCerts.gateway(TestCerts.COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestNumber\":1,\"usedMb\":%d}".formatted(usedMb)))
                .andExpect(status().isOk());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
