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
import org.springframework.xml.transform.StringResult;
import org.springframework.xml.transform.StringSource;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * Online charging over SOAP against Oracle: the same ChargingService as the REST API, reached
 * through StartSession / UpdateSession / ReleaseSession. Skipped when Docker isn't running.
 */
@IntegrationTest
class ChargingEndpointTest {

    private static final Map<String, String> NS = Map.of("s", WebServiceConfig.NAMESPACE);
    private static final String MSISDN = "94771234567";
    private static final Pattern SESSION_ID = Pattern.compile("<(?:\\w+:)?sessionId>([^<]+)<");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final Map<String, Long> requestNumbers = new HashMap<>();

    private MockWebServiceClient client;

    @BeforeEach
    void setUp() {
        client = MockWebServiceClient.createClient(context);
        TestData.clean(jdbc, redis);
    }

    @Test
    void startUpdateReleaseChargesOnlyWhatWasUsed() {
        createSubscriber("100.00");

        String session = sessionIdOf(start("GENERAL", 100)
                .andExpect(noFault())
                .andExpect(xpath("//s:grant/s:result", NS).evaluatesTo("ALLOW"))
                .andExpect(xpath("//s:grant/s:grantedMb", NS).evaluatesTo(100))
                .andExpect(xpath("//s:grant/s:source", NS).evaluatesTo("PLAN"))
                .andExpect(xpath("//s:grant/s:bandwidthKbps", NS).evaluatesTo(10240)));

        update(session, 100, 100)
                .andExpect(noFault())
                .andExpect(xpath("//s:grant/s:grantedMb", NS).evaluatesTo(100));

        release(session, 37)
                .andExpect(noFault())
                .andExpect(xpath("//s:totalUsedMb", NS).evaluatesTo(137));

        assertThat(jdbc.queryForObject("SELECT plan_data_used_mb FROM subscriber WHERE msisdn = ?", Long.class, MSISDN))
                .isEqualTo(137);
        assertThat(jdbc.queryForObject("SELECT plan_data_reserved_mb FROM subscriber WHERE msisdn = ?", Long.class, MSISDN))
                .isZero();
    }

    @Test
    void nothingLeftIsDenyWithBlock() {
        createSubscriber("0.00");
        start("GENERAL", 10240).andExpect(noFault());

        start("GENERAL", 100)
                .andExpect(noFault())
                .andExpect(xpath("//s:grant/s:result", NS).evaluatesTo("DENY"))
                .andExpect(xpath("//s:grant/s:grantedMb", NS).evaluatesTo(0))
                .andExpect(xpath("//s:grant/s:action", NS).evaluatesTo("BLOCK"))
                .andExpect(xpath("count(//s:grant/s:bandwidthKbps)", NS).evaluatesTo(0));
    }

    @Test
    void releasingTwiceIsClientFault() {
        createSubscriber("100.00");
        String session = sessionIdOf(start("GENERAL", 100));
        release(session, 10).andExpect(noFault());

        release(session, 10).andExpect(clientOrSenderFault("Charging session " + session + " is already released"));
    }

    @Test
    void unknownSessionIsClientFault() {
        update("00000000-0000-0000-0000-000000000000", 0, 100)
                .andExpect(clientOrSenderFault("Charging session 00000000-0000-0000-0000-000000000000 not found"));
    }

    @Test
    void chunkAboveLimitIsRejectedByXsd() {
        createSubscriber("100.00");
        start("GENERAL", 20000).andExpect(clientOrSenderFault());
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

    private ResponseActions start(String app, long requestedMb) {
        return client.sendRequest(withPayload(payload("""
                <s:StartSessionRequest xmlns:s='%s'>
                  <s:msisdn>%s</s:msisdn><s:app>%s</s:app><s:requestedMb>%d</s:requestedMb>
                </s:StartSessionRequest>""", MSISDN, app, requestedMb)));
    }

    private ResponseActions update(String sessionId, long usedMb, long requestedMb) {
        return client.sendRequest(withPayload(payload("""
                <s:UpdateSessionRequest xmlns:s='%s'>
                  <s:sessionId>%s</s:sessionId><s:requestNumber>%d</s:requestNumber>
                  <s:usedMb>%d</s:usedMb><s:requestedMb>%d</s:requestedMb>
                </s:UpdateSessionRequest>""", sessionId, nextRequestNumber(sessionId), usedMb, requestedMb)));
    }

    private ResponseActions release(String sessionId, long usedMb) {
        return client.sendRequest(withPayload(payload("""
                <s:ReleaseSessionRequest xmlns:s='%s'>
                  <s:sessionId>%s</s:sessionId><s:requestNumber>%d</s:requestNumber><s:usedMb>%d</s:usedMb>
                </s:ReleaseSessionRequest>""", sessionId, nextRequestNumber(sessionId), usedMb)));
    }

    // the gateway's counter per session: start is 0, then 1, 2, ...
    private long nextRequestNumber(String sessionId) {
        return requestNumbers.merge(sessionId, 1L, Long::sum);
    }

    // reads the sessionId from the response payload
    private static String sessionIdOf(ResponseActions response) {
        StringResult xml = new StringResult();
        response.andExpect((request, message) -> {
            try {
                javax.xml.transform.TransformerFactory.newInstance().newTransformer()
                        .transform(message.getPayloadSource(), xml);
            } catch (javax.xml.transform.TransformerException e) {
                throw new IllegalStateException(e);
            }
        });
        Matcher m = SESSION_ID.matcher(xml.toString());
        assertThat(m.find()).as("sessionId in %s", xml).isTrue();
        return m.group(1);
    }

    // the first %s in every payload is the namespace
    private static StringSource payload(String xml, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = WebServiceConfig.NAMESPACE;
        System.arraycopy(args, 0, all, 1, args.length);
        return new StringSource(xml.formatted(all));
    }
}
