package uz.orientadvertise.services.api.controller;

import org.springframework.security.core.Authentication;

/**
 * Caller-role helpers shared by the content/stats controllers — kept in one place so the
 * operator-only decision can't drift between {@code list}/{@code detail}/{@code stream}/
 * {@code delete}/{@code stats}. An admin+operator hybrid is deliberately NOT operator-only
 * (it resolves as admin → unrestricted, {@code canManage=true} everywhere).
 */
final class CallerRoles {

    private CallerRoles() {
    }

    /** True iff the sole relevant authority is {@code ROLE_OPERATOR} (no ADMIN/ADVERTISER/VIEWER). */
    static boolean isOperatorOnly(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        boolean hasOperator = false;
        boolean hasOther = false;
        for (var ga : auth.getAuthorities()) {
            String role = ga.getAuthority();
            if ("ROLE_OPERATOR".equals(role)) {
                hasOperator = true;
            } else if ("ROLE_ADMIN".equals(role) || "ROLE_ADVERTISER".equals(role)
                    || "ROLE_VIEWER".equals(role)) {
                hasOther = true;
            }
        }
        return hasOperator && !hasOther;
    }

    /** True iff the caller carries {@code ROLE_ADMIN} (incl. admin+operator hybrid). */
    static boolean isAdmin(Authentication auth) {
        return hasAuthority(auth, "ROLE_ADMIN");
    }

    static boolean hasAuthority(Authentication auth, String authority) {
        return auth != null && auth.getAuthorities() != null
                && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    static String usernameOf(Authentication auth) {
        return auth != null ? auth.getName() : null;
    }
}
