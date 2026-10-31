package com.thabendra.telecom.staff;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider staff tokens from Keycloak (PR-1). Staff log in there once (single sign-on, authorization
 * code + PKCE in the admin portal); this backend only checks the token:
 * <ul>
 *   <li>signature with Keycloak's public keys (RS256), iss, exp / nbf, and aud = telecom-ocs</li>
 *   <li>roles from realm_access.roles: only ADMIN and CARE_AGENT count, as ROLE_ADMIN / ROLE_CARE_AGENT;
 *       any other Keycloak role (offline_access, CUSTOMER, ...) gives nothing here</li>
 *   <li>the caller's name is preferred_username (e.g. agent1), which is what gets recorded as "changed by"</li>
 * </ul>
 */
@Component
public class StaffTokens {

    public static final String ADMIN = "ADMIN";
    public static final String CARE_AGENT = "CARE_AGENT";
    private static final Set<String> ROLES = Set.of(ADMIN, CARE_AGENT);

    private final StaffProperties properties;
    private final NimbusJwtDecoder decoder;

    public StaffTokens(StaffProperties properties) {
        this.properties = properties;
        this.decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        this.decoder.setJwtValidator(validator(properties));
    }

    public String issuer() {
        return properties.issuer();
    }

    public AuthenticationManager authenticationManager() {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter());
        return new ProviderManager(provider);
    }

    public static OAuth2TokenValidator<Jwt> validator(StaffProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(properties.audience())));
    }

    public static Converter<Jwt, AbstractAuthenticationToken> converter() {
        return jwt -> {
            String username = jwt.getClaimAsString("preferred_username");
            return new JwtAuthenticationToken(jwt, authorities(jwt), username != null ? username : jwt.getSubject());
        };
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Object roles = realmAccess == null ? null : realmAccess.get("roles");
        if (!(roles instanceof Collection<?> names)) {
            return List.of();
        }
        return names.stream()
                .map(String::valueOf)
                .filter(ROLES::contains)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
