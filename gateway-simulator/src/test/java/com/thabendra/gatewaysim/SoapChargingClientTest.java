package com.thabendra.gatewaysim;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The SOAP client against a mocked /ws: the envelope it sends, the answers it reads, faults and errors. */
class SoapChargingClientTest {

    private static final String SESSION = "9070f899-b1c9-4840-ab19-ca9d471eb017";
    private static final String WS = "http://backend/ws";

    private RestClient.Builder builder;
    private MockRestServiceServer backend;
    private SoapChargingClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        client = new SoapChargingClient(builder, "http://backend", "pgw-test-01");
    }

    @Test
    void startSendsSoapEnvelopeAndReadsTheGrant() {
        backend.expect(requestTo(WS))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString("text/xml")))
                .andExpect(header("SOAPAction", "\"\""))
                .andExpect(header("X-Client-Id", "pgw-test-01"))
                .andExpect(header("Idempotency-Key", "key-1"))
                .andExpect(content().string(containsString("http://schemas.xmlsoap.org/soap/envelope/")))
                .andExpect(content().string(containsString("<s:StartSessionRequest>")))
                .andExpect(content().string(containsString("<s:msisdn>94771234567</s:msisdn>")))
                .andExpect(content().string(containsString("<s:app>YOUTUBE</s:app>")))
                .andExpect(content().string(containsString("<s:requestedMb>100</s:requestedMb>")))
                .andRespond(soap("""
                        <ns2:StartSessionResponse xmlns:ns2="http://thabendra.com/telecom/subscriber">
                          <ns2:grant>
                            <ns2:sessionId>%s</ns2:sessionId>
                            <ns2:result>ALLOW</ns2:result>
                            <ns2:grantedMb>100</ns2:grantedMb>
                            <ns2:bandwidthKbps>2048</ns2:bandwidthKbps>
                            <ns2:source>PACKAGE</ns2:source>
                            <ns2:packageCode>YT_UNLIMITED</ns2:packageCode>
                            <ns2:validitySeconds>3600</ns2:validitySeconds>
                          </ns2:grant>
                        </ns2:StartSessionResponse>""".formatted(SESSION)));

        ChargingClient.Answer<ChargingClient.Grant> answer = client.start("key-1", "94771234567", "YOUTUBE", 100);

        backend.verify();
        assertThat(answer.replayed()).isFalse();   // first answer, not a replay
        assertThat(answer.body()).isEqualTo(new ChargingClient.Grant(SESSION, "ALLOW", 100, 2048, "PACKAGE",
                "YT_UNLIMITED", 3600, null));
    }

    @Test
    void replayedAnswerIsMarked() {
        HttpHeaders replayed = new HttpHeaders();
        replayed.add("Idempotent-Replayed", "true");
        backend.expect(requestTo(WS))
                .andRespond(withSuccess(envelope(grantResponse()), MediaType.TEXT_XML).headers(replayed));

        assertThat(client.start("key-1", "94771234567", "YOUTUBE", 100).replayed()).isTrue();
    }

    @Test
    void stillInProgressFaultIsRetryable409() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");
        backend.expect(requestTo(WS)).andRespond(withStatus(HttpStatus.CONFLICT).headers(headers)
                .contentType(MediaType.TEXT_XML)
                .body(envelope(faultBody("SOAP-ENV:Client", "Request with this Idempotency-Key is still being processed"))));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(e.getMessage()).contains("still being processed");
                    assertThat(RetryingCaller.isRetryable(e)).isTrue();
                });
    }

    @Test
    void denyGrantHasActionAndNoSpeed() {
        backend.expect(requestTo(WS))
                .andExpect(content().string(containsString("<s:UpdateSessionRequest>")))
                .andExpect(content().string(containsString("<s:sessionId>" + SESSION + "</s:sessionId>")))
                .andExpect(content().string(containsString("<s:requestNumber>3</s:requestNumber>")))
                .andExpect(content().string(containsString("<s:usedMb>60</s:usedMb>")))
                .andRespond(soap("""
                        <ns2:UpdateSessionResponse xmlns:ns2="http://thabendra.com/telecom/subscriber">
                          <ns2:grant>
                            <ns2:sessionId>%s</ns2:sessionId><ns2:result>DENY</ns2:result>
                            <ns2:grantedMb>0</ns2:grantedMb><ns2:action>BLOCK</ns2:action>
                          </ns2:grant>
                        </ns2:UpdateSessionResponse>""".formatted(SESSION)));

        ChargingClient.Grant grant = client.update("key-2", SESSION, 3, 60, 100).body();

        assertThat(grant.allowed()).isFalse();
        assertThat(grant.action()).isEqualTo("BLOCK");
        assertThat(grant.bandwidthKbps()).isNull();
        assertThat(grant.validitySeconds()).isNull();
    }

    @Test
    void releaseReadsTotalUsed() {
        backend.expect(requestTo(WS))
                .andExpect(content().string(containsString("<s:ReleaseSessionRequest>")))
                .andExpect(content().string(containsString("<s:requestNumber>2</s:requestNumber>")))
                .andRespond(soap("""
                        <ns2:ReleaseSessionResponse xmlns:ns2="http://thabendra.com/telecom/subscriber">
                          <ns2:sessionId>%s</ns2:sessionId><ns2:totalUsedMb>137</ns2:totalUsedMb>
                        </ns2:ReleaseSessionResponse>""".formatted(SESSION)));

        ChargingClient.Release release = client.release("key-3", SESSION, 2, 37).body();

        assertThat(release).isEqualTo(new ChargingClient.Release(SESSION, 137));
    }

    @Test
    void valuesAreXmlEscaped() {
        backend.expect(requestTo(WS))
                .andExpect(content().string(containsString("<s:app>A&amp;B&lt;</s:app>")))
                .andRespond(soap(grantResponse()));

        client.start("key-1", "94771234567", "A&B<", 100);

        backend.verify();
    }

    @Test
    void clientFaultBecomesFinal400WithTheFaultMessage() {
        backend.expect(requestTo(WS)).andRespond(fault("SOAP-ENV:Client",
                "Charging session " + SESSION + " not found"));

        assertThatThrownBy(() -> client.update("key-1", SESSION, 1, 0, 100))
                .isInstanceOfSatisfying(BackendCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(SoapChargingClient.CLIENT_FAULT);
                    assertThat(e.getMessage()).contains("Client fault", "not found");
                    assertThat(RetryingCaller.isRetryable(e)).isFalse();
                });
    }

    @Test
    void serverFaultBecomes500() {
        backend.expect(requestTo(WS)).andRespond(fault("SOAP-ENV:Server", "ORA-00060: deadlock"));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class,
                        e -> assertThat(e.status()).isEqualTo(SoapChargingClient.SERVER_FAULT));
    }

    @Test
    void unavailableKeepsStatusAndRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "2");
        backend.expect(requestTo(WS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(headers));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(RetryingCaller.isRetryable(e)).isTrue();
                });
    }

    @Test
    void timeoutIsNoAnswer() {
        backend.expect(requestTo(WS)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class,
                        e -> assertThat(e.status()).isEqualTo(BackendCallException.NO_ANSWER));
    }

    @Test
    void retryPolicyWorksOverSoapToo() {
        backend.expect(requestTo(WS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        backend.expect(requestTo(WS)).andRespond(soap(grantResponse()));
        RetryingCaller caller = new RetryingCaller(RetryPolicy.defaults(), wait -> { }, Clock.systemUTC(),
                new SimpleMeterRegistry());
        ChargingClient retrying = new RetryingChargingClient(client, caller);

        assertThat(retrying.start("key-1", "94771234567", "YOUTUBE", 100).body().allowed()).isTrue();

        backend.verify();
    }

    private static String grantResponse() {
        return """
                <ns2:StartSessionResponse xmlns:ns2="http://thabendra.com/telecom/subscriber">
                  <ns2:grant>
                    <ns2:sessionId>%s</ns2:sessionId><ns2:result>ALLOW</ns2:result><ns2:grantedMb>100</ns2:grantedMb>
                  </ns2:grant>
                </ns2:StartSessionResponse>""".formatted(SESSION);
    }

    private static ResponseCreator soap(String payload) {
        return withSuccess(envelope(payload), MediaType.TEXT_XML);
    }

    // Spring-WS answers a SOAP 1.1 fault with HTTP 500
    private static ResponseCreator fault(String code, String message) {
        return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_XML)
                .body(envelope(faultBody(code, message)));
    }

    private static String faultBody(String code, String message) {
        return """
                <SOAP-ENV:Fault>
                  <faultcode>%s</faultcode>
                  <faultstring xml:lang="en">%s</faultstring>
                </SOAP-ENV:Fault>""".formatted(code, message);
    }

    private static String envelope(String payload) {
        return """
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                  <SOAP-ENV:Header/>
                  <SOAP-ENV:Body>%s</SOAP-ENV:Body>
                </SOAP-ENV:Envelope>""".formatted(payload);
    }
}
