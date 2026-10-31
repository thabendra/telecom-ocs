package com.thabendra.telecom.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Who may call what (SE-1, step 3).
 * <ul>
 *   <li>Gateway API (/charging/**, /ws/**): the TLS handshake has already checked the certificate
 *       (our CA, not expired, a client certificate, private key proven). Here its name (CN) must be
 *       an allowed gateway, otherwise 403. The name becomes the caller's identity.</li>
 *   <li>Everything else: open for now; customer and provider logins come with CU / PR.</li>
 * </ul>
 * Machine calls carry no cookies and keep no session, so CSRF protection and sessions are off.
 */
@Configuration
public class SecurityConfig {

    public static final String GATEWAY_ROLE = "GATEWAY";

    @Bean
    @Order(1)
    SecurityFilterChain gatewayApi(HttpSecurity http, GatewayProperties gateways) throws Exception {
        http.securityMatcher(gatewayPaths(gateways))
                .x509(x509 -> x509
                        .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                        .userDetailsService(allowedGateways(gateways)))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(GATEWAY_ROLE))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicApi(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** A certificate name is a gateway only if it is on telecom.gateways.allowed. */
    @Bean
    UserDetailsService allowedGateways(GatewayProperties gateways) {
        return name -> {
            if (!gateways.allowed().contains(name)) {
                throw new UsernameNotFoundException("Gateway " + name + " is not allowed");
            }
            // no password: the private key proved who it is in the TLS handshake
            return User.withUsername(name).password("").roles(GATEWAY_ROLE).build();
        };
    }

    // plain Ant matchers: /ws is served by the SOAP servlet, not by Spring MVC
    private static RequestMatcher gatewayPaths(GatewayProperties gateways) {
        return new OrRequestMatcher(gateways.paths().stream()
                .map(pattern -> (RequestMatcher) AntPathRequestMatcher.antMatcher(pattern))
                .toList());
    }
}
