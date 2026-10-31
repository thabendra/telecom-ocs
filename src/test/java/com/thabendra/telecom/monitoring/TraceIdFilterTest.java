package com.thabendra.telecom.monitoring;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OB-3: the trace id goes back to the caller as X-Trace-Id, and nothing breaks without tracing. */
class TraceIdFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void theCurrentTraceIdIsInTheAnswer() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(tracer).doFilter(new MockHttpServletRequest("POST", "/charging/v1/sessions"), response,
                new MockFilterChain());

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(TRACE_ID);
    }

    @Test
    void withoutASpanThereIsNoHeader() throws Exception {
        Tracer tracer = mock(Tracer.class);   // currentSpan() is null
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(tracer).doFilter(new MockHttpServletRequest("GET", "/api/plans"), response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isNull();
    }

    @Test
    void withoutTracingAtAllTheRequestStillGoesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter(new StaticListableBeanFactory().getBeanProvider(Tracer.class))
                .doFilter(new MockHttpServletRequest("GET", "/api/plans"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isNull();
    }

    private static TraceIdFilter filter(Tracer tracer) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        return new TraceIdFilter(beans.getBeanProvider(Tracer.class));
    }
}
