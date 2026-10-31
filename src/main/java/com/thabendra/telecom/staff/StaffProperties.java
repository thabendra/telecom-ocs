package com.thabendra.telecom.staff;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * telecom.staff.* in application.yml: the SSO server provider staff log in at (PR-1, Keycloak).
 *
 * @param issuer    iss of its tokens, e.g. http://localhost:8180/realms/telecom
 * @param jwkSetUri its public keys; read on the first staff call, not at start-up
 * @param audience  aud the tokens must carry (Keycloak's audience mapper for this API)
 */
@ConfigurationProperties("telecom.staff")
public record StaffProperties(String issuer, String jwkSetUri, String audience) {
}
