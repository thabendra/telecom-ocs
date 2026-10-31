package com.thabendra.telecom.monitoring;

import com.thabendra.telecom.web.FilterOrder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * OB-3: every answer carries {@code X-Trace-Id}, the id of the trace this request belongs to. A gateway
 * that sent a W3C {@code traceparent} gets its own trace id back; a customer can quote it to customer
 * care, who find the whole request in Jaeger. Error bodies carry it too (ErrorResponses).
 * Without tracing (e.g. in tests) there is no span and no header.
 */
@Component
@Order(FilterOrder.TRACE_ID)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracer;

    public TraceIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span != null && !span.context().traceId().isBlank()) {
            response.setHeader(HEADER, span.context().traceId());   // before the body: headers can't come later
        }
        chain.doFilter(request, response);
    }
}
