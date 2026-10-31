package com.thabendra.telecom.staff;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-1: how a Keycloak access token becomes a staff member here (no Keycloak needed). */
class StaffTokensTest {

    private static final StaffProperties KEYCLOAK =
            new StaffProperties("http://localhost:8180/realms/telecom", "http://unused", "telecom-ocs");

    @Test
    void realmRolesBecomeSpringRolesAndTheLoginIsTheName() {
        AbstractAuthenticationToken staff = StaffTokens.converter().convert(token(
                List.of("CARE_AGENT", "offline_access", "default-roles-telecom"), "agent1", "telecom-ocs"));

        assertThat(staff.getName()).isEqualTo("agent1");
        assertThat(staff.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CARE_AGENT");   // other Keycloak roles mean nothing here
    }

    @Test
    void aCustomerRoleInAStaffTokenGivesNothing() {
        AbstractAuthenticationToken staff = StaffTokens.converter().convert(
                token(List.of("CUSTOMER"), "someone", "telecom-ocs"));

        assertThat(staff.getAuthorities()).isEmpty();
    }

    @Test
    void aTokenWithoutRealmRolesHasNoRoles() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("id-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();

        AbstractAuthenticationToken staff = StaffTokens.converter().convert(jwt);

        assertThat(staff.getAuthorities()).isEmpty();
        assertThat(staff.getName()).isEqualTo("id-1");   // no preferred_username: the subject
    }

    @Test
    void theRightIssuerAndAudiencePass() {
        assertThat(StaffTokens.validator(KEYCLOAK).validate(token(List.of("ADMIN"), "admin1", "telecom-ocs"))
                .hasErrors()).isFalse();
    }

    @Test
    void aTokenForAnotherApiIsRefused() {   // e.g. Keycloak's default "account" audience only
        assertThat(StaffTokens.validator(KEYCLOAK).validate(token(List.of("ADMIN"), "admin1", "account"))
                .hasErrors()).isTrue();
    }

    @Test
    void aTokenFromAnotherRealmIsRefused() {
        Jwt other = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer("http://localhost:8180/realms/master")
                .audience(List.of("telecom-ocs"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();

        assertThat(StaffTokens.validator(KEYCLOAK).validate(other).hasErrors()).isTrue();
    }

    // the shape of a Keycloak access token, as far as this backend reads it
    private static Jwt token(List<String> roles, String username, String audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(KEYCLOAK.issuer())
                .subject("5f0c6c1e-0000-4000-8000-000000000001")
                .audience(List.of(audience))
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
