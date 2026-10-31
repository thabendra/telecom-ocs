package com.thabendra.telecom.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error answers written by filters, before any controller runs: problem JSON (RFC 9457) for REST, a
 * SOAP Client fault for a SOAP caller (it can read a fault, not JSON). The HTTP status is the same
 * either way, so callers and their retry rules see 400 / 409 / 422 / 429 / 503 for both protocols.
 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                             String detail, boolean soap, ObjectMapper json) throws IOException {
        response.setStatus(status.value());
        if (soap) {
            response.setContentType(MediaType.TEXT_XML_VALUE + ";charset=UTF-8");
            response.getOutputStream().write(soapFault(detail).getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI().substring(request.getContextPath().length()));
        // OB-3: the id to quote to customer care; the same as the X-Trace-Id header
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            body.put("traceId", traceId);
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }

    private static String soapFault(String detail) {
        return """
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                  <SOAP-ENV:Body>
                    <SOAP-ENV:Fault>
                      <faultcode>SOAP-ENV:Client</faultcode>
                      <faultstring xml:lang="en">%s</faultstring>
                    </SOAP-ENV:Fault>
                  </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>""".formatted(HtmlUtils.htmlEscape(detail));
    }
}
