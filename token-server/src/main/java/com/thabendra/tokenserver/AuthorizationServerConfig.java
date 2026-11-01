package com.thabendra.tokenserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The token server for gateways (SE-2), built on Spring Authorization Server:
 * <ul>
 *   <li>grant: client credentials only (a machine, no user)</li>
 *   <li>client authentication: tls_client_auth (RFC 8705): the gateway proves its client_id with its
 *       mTLS certificate, whose subject must match the registered one; no shared secret to leak</li>
 *   <li>access token: a signed JWT, 5 minutes, aud=charging-api, the granted scopes (SE-5), and
 *       cnf.x5t#S256 = the certificate's SHA-256 thumbprint (SE-4), so it only works with that certificate</li>
 *   <li>/oauth2/jwks publishes the public keys the backend checks signatures with (SE-3), during a
 *       rotation more than one (SE-7)</li>
 * </ul>
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)   // machine clients, no browser, no cookies
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // nothing else is served
    @Bean
    @Order(2)
    SecurityFilterChain everythingElse(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    RegisteredClientRepository gatewayClients(TokenProperties props) {
        List<RegisteredClient> clients = props.clients().stream()
                .filter(TokenProperties.Client::enabled)   // SE-6
                .map(client -> RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(client.id())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.TLS_CLIENT_AUTH)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .scopes(scopes -> scopes.addAll(client.scopes()))
                        .clientSettings(ClientSettings.builder()
                                .x509CertificateSubjectDN(client.subject())
                                .build())
                        .tokenSettings(TokenSettings.builder()
                                .accessTokenTimeToLive(props.accessTokenTtl())
                                .x509CertificateBoundAccessTokens(true)
                                .build())
                        .build())
                .toList();
        return new InMemoryRegisteredClientRepository(clients);
    }

    // aud: the token is for the charging API only, not for any other service that trusts this server.
    // kid: which of the published keys signs, as there can be more than one (SE-7)
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> audience(TokenProperties props, SigningKeyStore keys) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(List.of(props.audience()));
                context.getJwsHeader().keyId(keys.signingKeyId());
            }
        };
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(TokenProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.issuer()).build();
    }

    @Bean
    SigningKeyStore signingKeys(TokenProperties props) {
        return new SigningKeyStore(props.signing(), Clock.systemUTC());
    }
}
