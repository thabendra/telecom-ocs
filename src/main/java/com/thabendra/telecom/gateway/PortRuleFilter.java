package com.thabendra.telecom.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps each API behind its own door: the gateway API only on the mTLS port, everything else only on
 * the public port. A wrong-door call gets 404, as if the path didn't exist there, so a gateway can't
 * skip the certificate check through the public port and a customer can't find the gateway API.
 * Runs before Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PortRuleFilter extends OncePerRequestFilter {

    private final ServerPorts ports;
    private final GatewayProperties gateways;
    private final AntPathMatcher paths = new AntPathMatcher();

    public PortRuleFilter(ServerPorts ports, GatewayProperties gateways) {
        this.ports = ports;
        this.gateways = gateways;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean gatewayPath = gateways.paths().stream().anyMatch(pattern -> paths.match(pattern, path));
        int port = request.getLocalPort();
        if ((gatewayPath && port == ports.publicPort()) || (!gatewayPath && port == ports.gatewayPort())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
