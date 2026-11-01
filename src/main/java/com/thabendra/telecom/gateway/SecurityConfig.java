package com.thabendra.telecom.gateway;

import com.thabendra.telecom.resilience.SigningKeysFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Who may call what (group 3).
 * <ul>
 *   <li>Gateway API (/charging/**, /ws/**), on the mTLS port:
 *     <ol>
 *       <li>TLS handshake: a certificate from our CA (SE-1)</li>
 *       <li>Authorization: Bearer access token from our token server: signature, iss, aud, exp (SE-3)</li>
 *       <li>token and certificate belong to the same allowed gateway, cnf matches (SE-1, SE-3, SE-4)</li>
 *       <li>the token has scope charging:write, or notify:read for the push channel (SE-5, PU-3)</li>
 *     </ol>
 *     The gateway name becomes the caller's identity.</li>
 *   <li>Everything else (the public port): customer and staff logins, see
 *       {@link com.thabendra.telecom.access.PublicApiSecurity} (group 6).</li>
 * </ul>
 * Machine calls carry no cookies and keep no session, so CSRF protection and sessions are off.
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain gatewayApi(HttpSecurity http, GatewayProperties gateways, JwtDecoder gatewayTokenDecoder)
            throws Exception {
        http.securityMatcher(gatewayPaths(gateways))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(gatewayTokenDecoder)))
                .addFilterAfter(new GatewayBindingFilter(gateways), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // the push channel (PU-3) reads commands: notify:read
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/charging/v1/commands/**"))
                        .hasAuthority("SCOPE_" + gateways.token().notifyScope())
                        // everything else is charging: charging:write
                        .anyRequest().hasAuthority("SCOPE_" + gateways.token().scope()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Checks a gateway's access token with the token server's public keys (/oauth2/jwks, read over HTTPS
     * trusting only the dev CA). Fetched through a circuit breaker, and the last good keys stay in use
     * while the token server is down (CB-2).
     */
    @Bean
    JwtDecoder gatewayTokenDecoder(GatewayProperties gateways, RestTemplateBuilder rest, SslBundles bundles,
                                   SigningKeysFactory signingKeys) {
        NimbusJwtDecoder decoder = signingKeys.decoder("token-server", gateways.token().jwkSetUri(),
                rest.sslBundle(bundles.getBundle("token-server-client")));
        decoder.setJwtValidator(GatewayTokens.validator(gateways.token()));
        return decoder;
    }

    // plain Ant matchers: /ws is served by the SOAP servlet, not by Spring MVC
    private static RequestMatcher gatewayPaths(GatewayProperties gateways) {
        return new OrRequestMatcher(gateways.paths().stream()
                .map(pattern -> (RequestMatcher) AntPathRequestMatcher.antMatcher(pattern))
                .toList());
    }
}
