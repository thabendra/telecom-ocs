package com.thabendra.telecom.soap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.validPayload;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * End to end over SOAP against a real Oracle Database Free container:
 * XSD validation, Flyway migrations, JPA and the CHANGE_PLAN PL/SQL procedure.
 * Skipped when Docker isn't running.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SubscriberEndpointTest {

    @Container
    @ServiceConnection
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(Duration.ofMinutes(5));   // first start on a laptop can be slow

    private static final Map<String, String> NS = Map.of("s", WebServiceConfig.NAMESPACE);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockWebServiceClient client;

    @BeforeEach
    void setUp() {
        client = MockWebServiceClient.createClient(context);
        jdbc.update("DELETE FROM usage_record");
        jdbc.update("DELETE FROM subscriber_package");
        jdbc.update("DELETE FROM plan_change");
        jdbc.update("DELETE FROM subscriber");
    }

    @Test
    void listPlansReturnsSeededPlans() throws Exception {
        client.sendRequest(withPayload(payload("<s:ListPlansRequest xmlns:s='%s'/>")))
                .andExpect(noFault())
                .andExpect(validPayload(new ClassPathResource("xsd/subscriber.xsd")))
                .andExpect(xpath("count(//s:plan)", NS).evaluatesTo(3));
    }

    @Test
    void createTopUpAndChangePlan() {
        createSubscriber("94771234567", "500.00");

        client.sendRequest(withPayload(payload("""
                        <s:TopUpRequest xmlns:s='%s'>
                          <s:msisdn>94771234567</s:msisdn><s:amount>1000.00</s:amount>
                        </s:TopUpRequest>""")))
                .andExpect(noFault())
                .andExpect(xpath("//s:balance", NS).evaluatesTo(1500.0));

        client.sendRequest(withPayload(payload("""
                        <s:ChangePlanRequest xmlns:s='%s'>
                          <s:msisdn>94771234567</s:msisdn><s:newPlanCode>SMART</s:newPlanCode>
                        </s:ChangePlanRequest>""")))
                .andExpect(noFault())
                .andExpect(xpath("//s:subscriber/s:plan/s:code", NS).evaluatesTo("SMART"))
                .andExpect(xpath("//s:subscriber/s:balance", NS).evaluatesTo(301.0));

        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM plan_change", Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void changePlanWithLowBalanceIsClientFaultAndChangesNothing() {
        createSubscriber("94771111111", "100.00");

        client.sendRequest(withPayload(payload("""
                        <s:ChangePlanRequest xmlns:s='%s'>
                          <s:msisdn>94771111111</s:msisdn><s:newPlanCode>MAX</s:newPlanCode>
                        </s:ChangePlanRequest>""")))
                .andExpect(clientOrSenderFault("Insufficient balance: 100, plan fee is 2499"));

        String plan = jdbc.queryForObject(
                "SELECT p.code FROM subscriber s JOIN plan p ON p.id = s.plan_id WHERE s.msisdn = ?",
                String.class, "94771111111");
        assertThat(plan).isEqualTo("BASIC");
    }

    @Test
    void unknownSubscriberIsClientFault() {
        client.sendRequest(withPayload(payload("""
                        <s:GetSubscriberRequest xmlns:s='%s'><s:msisdn>94770000000</s:msisdn></s:GetSubscriberRequest>""")))
                .andExpect(clientOrSenderFault("Subscriber 94770000000 not found"));
    }

    @Test
    void invalidMsisdnIsRejectedByXsd() {
        client.sendRequest(withPayload(payload("""
                        <s:GetSubscriberRequest xmlns:s='%s'><s:msisdn>0771234567</s:msisdn></s:GetSubscriberRequest>""")))
                .andExpect(clientOrSenderFault());
    }

    private void createSubscriber(String msisdn, String balance) {
        client.sendRequest(withPayload(payload("""
                        <s:CreateSubscriberRequest xmlns:s='%s'>
                          <s:msisdn>%s</s:msisdn>
                          <s:fullName>Kamal Perera</s:fullName>
                          <s:nic>200012345678</s:nic>
                          <s:planCode>BASIC</s:planCode>
                          <s:initialBalance>%s</s:initialBalance>
                        </s:CreateSubscriberRequest>""", msisdn, balance)))
                .andExpect(noFault());
    }

    // the first %s in every payload is the namespace
    private static StringSource payload(String xml, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = WebServiceConfig.NAMESPACE;
        System.arraycopy(args, 0, all, 1, args.length);
        return new StringSource(xml.formatted(all));
    }
}
