package tz.go.tirdo.cbrms.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Staff roles need an MFA sign-in (acr=mfa) when cbrms.security.staff-acr is set; system roles never do. */
class StaffSignInLevelTest {

    private static Jwt token(String user, String acr, String... roles) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none")
                .claim("preferred_username", user)
                .claim("realm_access", Map.of("roles", List.of(roles)));
        if (acr != null) {
            b.claim("acr", acr);
        }
        return b.build();
    }

    private static Set<String> roles(Set<String> staffAcr, Jwt jwt) {
        Set<String> out = new TreeSet<>();
        for (GrantedAuthority a : SecurityConfig.keycloakConverter(staffAcr).convert(jwt).getAuthorities()) {
            // Spring Security 7 also adds its own factor authorities (FACTOR_BEARER); only roles matter here
            if (a.getAuthority().startsWith("ROLE_")) {
                out.add(a.getAuthority());
            }
        }
        return out;
    }

    @Test
    void staffWithMfaKeepTheirRoles() {
        assertEquals(Set.of("ROLE_BILL_APPROVER", "ROLE_OFFLINE_ACCESS"),
                roles(Set.of("mfa"), token("finance.approver", "mfa", "BILL_APPROVER", "offline_access")));
    }

    @Test
    void staffWithPasswordOnlyLoseStaffRoles() {
        assertEquals(Set.of("ROLE_OFFLINE_ACCESS"),
                roles(Set.of("mfa"), token("finance.maker", "password", "BILLING_OFFICER", "FINANCE_VIEWER", "offline_access")));
        assertEquals(Set.of(), roles(Set.of("mfa"), token("internal.auditor", null, "AUDITOR")));
    }

    @Test
    void systemAccountsAreNotAffected() {
        assertEquals(Set.of("ROLE_PLATFORM"), roles(Set.of("mfa"), token("service-account-lims", "password", "PLATFORM")));
        assertEquals(Set.of("ROLE_TECHNICAL_OPERATOR"),
                roles(Set.of("mfa"), token("service-account-monitoring", "1", "TECHNICAL_OPERATOR")));
    }

    @Test
    void notEnforcedWhenEmpty() {
        assertEquals(Set.of("ROLE_BILLING_OFFICER"), roles(Set.of(), token("finance.maker", "password", "BILLING_OFFICER")));
    }

    @Test
    void levelsParseFromCommaSeparatedSetting() {
        assertEquals(Set.of("mfa", "2"), SecurityConfig.parseLevels(" mfa, 2 ,"));
        assertEquals(Set.of(), SecurityConfig.parseLevels(""));
        assertEquals(Set.of(), SecurityConfig.parseLevels(null));
    }

    @Test
    void principalIsTheUsername() {
        assertEquals("finance.approver",
                SecurityConfig.keycloakConverter(Set.of("mfa")).convert(token("finance.approver", "mfa", "BILL_APPROVER")).getName());
    }
}
