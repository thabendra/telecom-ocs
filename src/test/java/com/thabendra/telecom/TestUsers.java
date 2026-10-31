package com.thabendra.telecom;

import com.thabendra.telecom.customer.CustomerTokens;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Logged-in callers of the public API for MockMvc tests (group 6), without Keycloak or an SMS code: the
 * request is simply marked as coming from this person. How real tokens are checked is tested in
 * CustomerLoginTest (real customer tokens) and StaffTokensTest (Keycloak's token shape).
 */
public final class TestUsers {

    public static final String ADMIN = "admin1";
    public static final String CARE_AGENT = "agent1";

    private TestUsers() {
    }

    public static RequestPostProcessor admin() {
        return staff(ADMIN, "ROLE_ADMIN");
    }

    public static RequestPostProcessor careAgent() {
        return staff(CARE_AGENT, "ROLE_CARE_AGENT");
    }

    public static RequestPostProcessor customer(String msisdn) {
        return jwt().jwt(token -> token.subject(msisdn)).authorities(new SimpleGrantedAuthority(CustomerTokens.ROLE));
    }

    private static RequestPostProcessor staff(String login, String role) {
        return jwt().jwt(token -> token.subject(login).claim("preferred_username", login))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
