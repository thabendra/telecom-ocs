package com.thabendra.telecom.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Errors written by filters: problem JSON with the trace id to quote (OB-3), or a SOAP fault. */
class ErrorResponsesTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void problemJsonCarriesTheTraceId() throws Exception {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ErrorResponses.write(new MockHttpServletRequest("POST", "/charging/v1/sessions"), response,
                HttpStatus.TOO_MANY_REQUESTS, "slow down", false, new ObjectMapper());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString())
                .contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
                .contains("\"instance\":\"/charging/v1/sessions\"");
    }

    @Test
    void withoutATraceThereIsNoTraceIdField() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ErrorResponses.write(new MockHttpServletRequest("GET", "/api/plans"), response,
                HttpStatus.SERVICE_UNAVAILABLE, "busy", false, new ObjectMapper());

        assertThat(response.getContentAsString()).doesNotContain("traceId");
    }

    @Test
    void aSoapCallerGetsAFaultWithTheSameStatus() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ErrorResponses.write(new MockHttpServletRequest("POST", "/ws"), response,
                HttpStatus.TOO_MANY_REQUESTS, "slow <down>", true, new ObjectMapper());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("SOAP-ENV:Fault", "slow &lt;down&gt;");
    }
}
