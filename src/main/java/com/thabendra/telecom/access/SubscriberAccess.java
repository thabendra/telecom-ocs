package com.thabendra.telecom.access;

import com.thabendra.telecom.customer.CustomerTokens;
import com.thabendra.telecom.staff.StaffTokens;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Who may touch one subscriber's data at /api/subscribers/{msisdn}/** (CU-1):
 * provider staff for any number, a customer only for the number in their own token.
 * Without this, a customer could change the number in the URL and read someone else's quota
 * (an IDOR: insecure direct object reference).
 */
public final class SubscriberAccess {

    private SubscriberAccess() {
    }

    public static AuthorizationManager<RequestAuthorizationContext> ownerOrStaff() {
        return (authentication, context) -> {
            Authentication caller = authentication.get();
            String msisdn = context.getVariables().get("msisdn");
            return new AuthorizationDecision(isStaff(caller) || isOwner(caller, msisdn));
        };
    }

    static boolean isOwner(Authentication caller, String msisdn) {
        return has(caller, CustomerTokens.ROLE) && caller.getName() != null && caller.getName().equals(msisdn);
    }

    static boolean isStaff(Authentication caller) {
        return has(caller, "ROLE_" + StaffTokens.ADMIN) || has(caller, "ROLE_" + StaffTokens.CARE_AGENT);
    }

    private static boolean has(Authentication caller, String authority) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority granted : caller.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
