package com.thabendra.telecom.access;

import com.thabendra.telecom.customer.CustomerTokens;
import com.thabendra.telecom.staff.StaffTokens;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Who may call what on the public port (group 6). Two kinds of people log in, each with its own token:
 * <ul>
 *   <li>customers: a token from this backend after an SMS code (CU-1), name = their number</li>
 *   <li>provider staff: a Keycloak token (PR-1), name = their login, role ADMIN or CARE_AGENT</li>
 * </ul>
 * The token's iss decides which checker reads it, and each checker only trusts its own key and
 * algorithm (HS256 for customers, Keycloak's RS256 keys for staff), so a customer can't make a token
 * that passes as staff, and a token from any other issuer is 401.
 * <pre>
 *   POST /api/auth/**                          anyone (the login itself)
 *   GET  /api/plans, /api/packages             anyone (the public catalog)
 *   /api/admin/**                              ADMIN (packages, prices, speeds: PR-2)
 *   POST /api/subscribers                      staff (register a SIM)
 *   /api/subscribers/{msisdn}/suspension       staff (PR-2; pushes BLOCK / UNBLOCK, group 5)
 *   GET  /api/subscribers/{msisdn}/audit       staff (OB-1: what happened to this number)
 *   POST /api/subscribers/{msisdn}/usage       ADMIN (test tool: usage as if from the network)
 *   /api/subscribers/{msisdn}/**               staff, or the customer whose number it is (CU-2)
 *   anything else                              nobody
 * </pre>
 * Tokens only, no cookies or sessions, so CSRF protection and sessions are off.
 */
@Configuration
public class PublicApiSecurity {

    private static final String[] STAFF = {StaffTokens.ADMIN, StaffTokens.CARE_AGENT};

    @Bean
    @Order(2)
    SecurityFilterChain publicApi(HttpSecurity http, CustomerTokens customers, StaffTokens staff) throws Exception {
        Map<String, AuthenticationManager> byIssuer = Map.of(
                customers.issuer(), customers.authenticationManager(),
                staff.issuer(), staff.authenticationManager());
        http.oauth2ResourceServer(resource -> resource.authenticationManagerResolver(
                        new JwtIssuerAuthenticationManagerResolver(byIssuer::get)))
                .authorizeHttpRequests(auth -> auth
                        // error pages (e.g. the 404 of the port rule) answer whoever asked
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/auth/**")).permitAll()
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/plans"),
                                antMatcher(HttpMethod.GET, "/api/packages")).permitAll()
                        .requestMatchers(antMatcher("/api/admin/**")).hasRole(StaffTokens.ADMIN)
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/subscribers")).hasAnyRole(STAFF)
                        .requestMatchers(antMatcher("/api/subscribers/{msisdn}/suspension")).hasAnyRole(STAFF)
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/subscribers/{msisdn}/audit")).hasAnyRole(STAFF)
                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/subscribers/{msisdn}/usage"))
                        .hasRole(StaffTokens.ADMIN)
                        .requestMatchers(antMatcher("/api/subscribers/{msisdn}/**")).access(SubscriberAccess.ownerOrStaff())
                        .anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
