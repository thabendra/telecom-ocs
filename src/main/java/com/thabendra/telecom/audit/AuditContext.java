package com.thabendra.telecom.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Tells Oracle who is calling, so PL/SQL can write it into the audit trail (OB-1). The caller's name
 * (the gateway, from its certificate + token) goes into the Oracle client identifier of this database
 * session with DBMS_SESSION.SET_IDENTIFIER; the CHARGING package reads it back with
 * SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER').
 * <p>
 * Connections come from a pool and serve other requests afterwards, so the identifier is cleared again
 * right after the call. Must run inside the caller's transaction: then it is the same connection.
 */
@Component
public class AuditContext {

    private final JdbcTemplate jdbc;

    public AuditContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> T asCaller(Supplier<T> work) {
        jdbc.update("BEGIN DBMS_SESSION.SET_IDENTIFIER(?); END;", callerName());
        try {
            return work.get();
        } finally {
            jdbc.update("BEGIN DBMS_SESSION.CLEAR_IDENTIFIER; END;");
        }
    }

    // the verified gateway name on the gateway port; "system" when nobody is logged in (e.g. a test)
    public static String callerName() {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller == null || !caller.isAuthenticated() || caller instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return caller.getName();
    }
}
