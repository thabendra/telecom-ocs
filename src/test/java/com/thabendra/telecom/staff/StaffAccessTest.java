package com.thabendra.telecom.staff;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR-1 / PR-2 against Oracle: what ADMIN and CARE_AGENT may do, creating and pricing packages (price
 * history by the V9 trigger), speeds, stopping a sale, and suspensions recording the staff member.
 * Skipped when Docker isn't running.
 */
@IntegrationTest
class StaffAccessTest {

    private static final String MSISDN = "94771234567";
    private static final String PACKAGE = TestData.TEST_PACKAGE_PREFIX + "TIKTOK";
    private static final int BASIC_SPEED = 10240;   // seeded BASIC plan (V2)

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").with(TestUsers.careAgent()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":1000.00}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    // the plans are seeded and shared by every test class: put the speed back
    @AfterEach
    void restorePlanSpeed() {
        jdbc.update("UPDATE plan SET max_bandwidth_kbps = ?, updated_by = NULL, updated_at = NULL WHERE code = 'BASIC'",
                BASIC_SPEED);
    }

    // PR-1: roles

    @Test
    void aCareAgentSuspendsAndTheirLoginIsKept() throws Exception {
        mvc.perform(put("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"FRAUD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThat(jdbc.queryForObject("SELECT status_changed_by FROM subscriber WHERE msisdn = ?",
                String.class, MSISDN)).isEqualTo(TestUsers.CARE_AGENT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_command WHERE msisdn = ? AND action = 'BLOCK'",
                Integer.class, MSISDN)).isEqualTo(2);   // group 5: both gateways
    }

    @Test
    void aCareAgentCannotChangeTheCatalog() throws Exception {
        createPackage(TestUsers.careAgent()).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/packages/{code}/price", "WA_2GB").with(TestUsers.careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/subscribers/{msisdn}/usage", MSISDN).with(TestUsers.careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"app\":\"GENERAL\",\"dataMb\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanReadAnySubscriber() throws Exception {
        mvc.perform(get("/api/subscribers/{msisdn}/quota", MSISDN).with(TestUsers.careAgent()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/subscribers/{msisdn}", MSISDN).with(TestUsers.admin()))
                .andExpect(status().isOk());
    }

    @Test
    void aCustomerCannotRegisterSubscribers() throws Exception {
        mvc.perform(post("/api/subscribers").with(TestUsers.customer(MSISDN)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"94770000001","fullName":"Someone Else","nic":"200012345679",
                                 "planCode":"BASIC"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownPathIsClosed() throws Exception {
        mvc.perform(get("/api/anything").with(TestUsers.admin())).andExpect(status().isForbidden());
    }

    // PR-2: packages, prices, speeds

    @Test
    void anAdminCreatesAPackageThatCustomersCanBuy() throws Exception {
        createPackage(TestUsers.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.updatedBy").value(TestUsers.ADMIN));

        mvc.perform(get("/api/packages")).andExpect(jsonPath("$[*].code", hasItem(PACKAGE)));
        buy(PACKAGE).andExpect(status().isCreated());
    }

    @Test
    void theSameCodeTwiceIs409() throws Exception {
        createPackage(TestUsers.admin()).andExpect(status().isCreated());
        createPackage(TestUsers.admin()).andExpect(status().isConflict());
    }

    @Test
    void fairUsageWithoutAThrottledSpeedBreaksTheCatalogRule() throws Exception {
        mvc.perform(post("/api/admin/packages").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Broken","app":"YOUTUBE","price":100,"validityDays":30,
                                 "maxBandwidthKbps":2048,"fairUsageMb":1024}""".formatted(PACKAGE)))
                .andExpect(status().isConflict());
    }

    @Test
    void everyPriceIsKeptWithWhoSetIt() throws Exception {   // the V9 trigger
        createPackage(TestUsers.admin()).andExpect(status().isCreated());

        mvc.perform(put("/api/admin/packages/{code}/price", PACKAGE).with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":149.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(149.0));

        mvc.perform(get("/api/admin/packages/{code}/prices", PACKAGE).with(TestUsers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].oldPrice").doesNotExist())
                .andExpect(jsonPath("$[0].newPrice").value(99.0))
                .andExpect(jsonPath("$[1].oldPrice").value(99.0))
                .andExpect(jsonPath("$[1].newPrice").value(149.0))
                .andExpect(jsonPath("$[1].changedBy").value(TestUsers.ADMIN));
    }

    @Test
    void anAdminSetsAPackagesSpeed() throws Exception {
        createPackage(TestUsers.admin()).andExpect(status().isCreated());

        mvc.perform(put("/api/admin/packages/{code}/speed", PACKAGE).with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxBandwidthKbps\":4096}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBandwidthKbps").value(4096));
        // this package has no fair usage limit, so no throttled speed
        mvc.perform(put("/api/admin/packages/{code}/speed", PACKAGE).with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxBandwidthKbps\":4096,\"throttledBandwidthKbps\":512}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aPackageNoLongerSoldIsHiddenAndCannotBeBought() throws Exception {
        createPackage(TestUsers.admin()).andExpect(status().isCreated());

        mvc.perform(put("/api/admin/packages/{code}/status", PACKAGE).with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/packages")).andExpect(jsonPath("$[*].code", not(hasItem(PACKAGE))));
        mvc.perform(get("/api/admin/packages").with(TestUsers.admin())).andExpect(jsonPath("$[*].code", hasItem(PACKAGE)));
        buy(PACKAGE).andExpect(status().isConflict());   // ORA-20010 from ACTIVATE_PACKAGE
    }

    @Test
    void anUnknownPackageIs404() throws Exception {
        mvc.perform(put("/api/admin/packages/{code}/price", "NO_SUCH").with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNewPlanSpeedIsWhatUnsuspendSendsToTheGateways() throws Exception {
        mvc.perform(put("/api/admin/plans/{code}/speed", "BASIC").with(TestUsers.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxBandwidthKbps\":15360}"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.careAgent()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("""
                SELECT bandwidth_kbps FROM gateway_command WHERE msisdn = ? AND action = 'UNBLOCK'""",
                Integer.class, MSISDN)).hasSize(2).containsOnly(15360);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM plan WHERE code = 'BASIC'", String.class))
                .isEqualTo(TestUsers.ADMIN);
    }

    private ResultActions createPackage(RequestPostProcessor who) throws Exception {
        return mvc.perform(post("/api/admin/packages").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"%s","name":"TikTok 3GB 30 Days","price":99.00,"dataMb":3072,
                         "validityDays":30,"maxBandwidthKbps":2048}""".formatted(PACKAGE)));
    }

    private ResultActions buy(String code) throws Exception {
        return mvc.perform(post("/api/subscribers/{msisdn}/packages", MSISDN).with(TestUsers.customer(MSISDN))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageCode\":\"%s\"}".formatted(code)));
    }
}
