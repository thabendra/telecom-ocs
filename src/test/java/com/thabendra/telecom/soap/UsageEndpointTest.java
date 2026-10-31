package com.thabendra.telecom.soap;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.ws.test.server.ResponseActions;
import org.springframework.xml.transform.StringSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * Usage charging end to end over SOAP against Oracle: which bucket pays for which app,
 * splitting across buckets, pay as you go, fair usage throttling and the speed policy returned.
 * Skipped when Docker isn't running.
 */
@IntegrationTest
class UsageEndpointTest {

    private static final Map<String, String> NS = Map.of("s", WebServiceConfig.NAMESPACE);
    private static final String MSISDN = "94771234567";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private MockWebServiceClient client;

    @BeforeEach
    void setUp() {
        client = MockWebServiceClient.createClient(context);
        TestData.clean(jdbc, redis);
    }

    @Test
    void youtubeTrafficUsesYoutubePackAtItsSpeed() {
        createSubscriber("2000.00");
        activate("YT_UNLIMITED");

        recordUsage("YOUTUBE", 300)
                .andExpect(noFault())
                .andExpect(xpath("count(//s:portion)", NS).evaluatesTo(1))
                .andExpect(xpath("//s:portion/s:source", NS).evaluatesTo("PACKAGE"))
                .andExpect(xpath("//s:portion/s:packageCode", NS).evaluatesTo("YT_UNLIMITED"))
                .andExpect(xpath("//s:bandwidthKbps", NS).evaluatesTo(2048))
                .andExpect(xpath("//s:moneyCharged", NS).evaluatesTo(0.0));

        assertThat(planDataUsedMb()).isZero();
    }

    @Test
    void generalTrafficUsesAddOnBeforePlan() {
        createSubscriber("2500.00");
        activate("YT_UNLIMITED");
        activate("EXTRA_25GB");

        recordUsage("GENERAL", 500)
                .andExpect(noFault())
                .andExpect(xpath("//s:portion/s:packageCode", NS).evaluatesTo("EXTRA_25GB"))
                .andExpect(xpath("//s:bandwidthKbps", NS).evaluatesTo(20480));
    }

    @Test
    void appWithoutItsOwnPackUsesPlanData() {
        createSubscriber("500.00");
        activate("WA_2GB");

        recordUsage("YOUTUBE", 400)
                .andExpect(noFault())
                .andExpect(xpath("//s:portion/s:source", NS).evaluatesTo("PLAN"))
                .andExpect(xpath("//s:bandwidthKbps", NS).evaluatesTo(10240));

        assertThat(planDataUsedMb()).isEqualTo(400);
    }

    @Test
    void usageSplitsWhenPackRunsOut() {
        createSubscriber("500.00");
        activate("WA_2GB");

        recordUsage("WHATSAPP", 2100)
                .andExpect(noFault())
                .andExpect(xpath("count(//s:portion)", NS).evaluatesTo(2))
                .andExpect(xpath("//s:portion[1]/s:packageCode", NS).evaluatesTo("WA_2GB"))
                .andExpect(xpath("//s:portion[1]/s:dataMb", NS).evaluatesTo(2048))
                .andExpect(xpath("//s:portion[2]/s:source", NS).evaluatesTo("PLAN"))
                .andExpect(xpath("//s:portion[2]/s:dataMb", NS).evaluatesTo(52));
    }

    @Test
    void allDataUsedChargesBalancePayAsYouGo() {
        createSubscriber("100.00");

        // BASIC has 10GB = 10240 MB; 60 MB more at 0.50 LKR/MB = 30 LKR
        recordUsage("GENERAL", 10300)
                .andExpect(noFault())
                .andExpect(xpath("//s:portion[2]/s:source", NS).evaluatesTo("BALANCE"))
                .andExpect(xpath("//s:portion[2]/s:dataMb", NS).evaluatesTo(60))
                .andExpect(xpath("//s:moneyCharged", NS).evaluatesTo(30.0))
                .andExpect(xpath("//s:balance", NS).evaluatesTo(70.0))
                .andExpect(xpath("//s:bandwidthKbps", NS).evaluatesTo(1024));
    }

    @Test
    void noDataAndNoBalanceIsFaultAndChargesNothing() {
        createSubscriber("10.00");

        recordUsage("GENERAL", 10300)
                .andExpect(clientOrSenderFault());

        assertThat(planDataUsedMb()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_record", Integer.class)).isZero();
    }

    @Test
    void unlimitedPackIsThrottledAfterFairUsage() {
        createSubscriber("1000.00");
        activate("YT_UNLIMITED");

        recordUsage("YOUTUBE", 51200)
                .andExpect(noFault())
                .andExpect(xpath("//s:bandwidthKbps", NS).evaluatesTo(512));
    }

    @Test
    void quotaListsBucketsInChargingOrder() {
        createSubscriber("3000.00");
        activate("EXTRA_25GB");
        activate("YT_UNLIMITED");
        recordUsage("GENERAL", 600).andExpect(noFault());

        client.sendRequest(withPayload(payload(
                        "<s:GetQuotaRequest xmlns:s='%s'><s:msisdn>%s</s:msisdn></s:GetQuotaRequest>", MSISDN)))
                .andExpect(noFault())
                .andExpect(xpath("count(//s:bucket)", NS).evaluatesTo(3))
                .andExpect(xpath("//s:bucket[1]/s:code", NS).evaluatesTo("YT_UNLIMITED"))
                .andExpect(xpath("count(//s:bucket[1]/s:dataMbLeft)", NS).evaluatesTo(0))   // unlimited
                .andExpect(xpath("//s:bucket[2]/s:code", NS).evaluatesTo("EXTRA_25GB"))
                .andExpect(xpath("//s:bucket[2]/s:dataMbLeft", NS).evaluatesTo(25000))
                .andExpect(xpath("//s:bucket[3]/s:source", NS).evaluatesTo("PLAN"))
                .andExpect(xpath("//s:bucket[3]/s:dataMbLeft", NS).evaluatesTo(10240));
    }

    @Test
    void changingPlanRefillsPlanData() {
        createSubscriber("2000.00");
        recordUsage("GENERAL", 1000).andExpect(noFault());

        client.sendRequest(withPayload(payload("""
                        <s:ChangePlanRequest xmlns:s='%s'>
                          <s:msisdn>%s</s:msisdn><s:newPlanCode>SMART</s:newPlanCode>
                        </s:ChangePlanRequest>""", MSISDN)))
                .andExpect(noFault());

        assertThat(planDataUsedMb()).isZero();
    }

    @Test
    void unknownPackageIsClientFault() {
        createSubscriber("2000.00");

        client.sendRequest(withPayload(payload("""
                        <s:ActivatePackageRequest xmlns:s='%s'>
                          <s:msisdn>%s</s:msisdn><s:packageCode>NETFLIX</s:packageCode>
                        </s:ActivatePackageRequest>""", MSISDN)))
                .andExpect(clientOrSenderFault("Package NETFLIX not found"));
    }

    private void createSubscriber(String balance) {
        client.sendRequest(withPayload(payload("""
                        <s:CreateSubscriberRequest xmlns:s='%s'>
                          <s:msisdn>%s</s:msisdn>
                          <s:fullName>Kamal Perera</s:fullName>
                          <s:nic>200012345678</s:nic>
                          <s:planCode>BASIC</s:planCode>
                          <s:initialBalance>%s</s:initialBalance>
                        </s:CreateSubscriberRequest>""", MSISDN, balance)))
                .andExpect(noFault());
    }

    private void activate(String packageCode) {
        client.sendRequest(withPayload(payload("""
                        <s:ActivatePackageRequest xmlns:s='%s'>
                          <s:msisdn>%s</s:msisdn><s:packageCode>%s</s:packageCode>
                        </s:ActivatePackageRequest>""", MSISDN, packageCode)))
                .andExpect(noFault());
    }

    private ResponseActions recordUsage(String app, long dataMb) {
        return client.sendRequest(withPayload(payload("""
                <s:RecordUsageRequest xmlns:s='%s'>
                  <s:msisdn>%s</s:msisdn><s:app>%s</s:app><s:dataMb>%d</s:dataMb>
                </s:RecordUsageRequest>""", MSISDN, app, dataMb)));
    }

    private long planDataUsedMb() {
        return jdbc.queryForObject("SELECT plan_data_used_mb FROM subscriber WHERE msisdn = ?", Long.class, MSISDN);
    }

    // the first %s in every payload is the namespace
    private static StringSource payload(String xml, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = WebServiceConfig.NAMESPACE;
        System.arraycopy(args, 0, all, 1, args.length);
        return new StringSource(xml.formatted(all));
    }
}
