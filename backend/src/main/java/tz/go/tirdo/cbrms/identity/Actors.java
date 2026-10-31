package tz.go.tirdo.cbrms.identity;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tz.go.tirdo.cbrms.common.ApiException;

@Component
public class Actors {
    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final Set<String> FINANCE_ROLES = Set.of("ROLE_BILLING_OFFICER", "ROLE_BILL_APPROVER", "ROLE_FINANCE_VIEWER", "ROLE_AUDITOR");

    private final JdbcClient jdbc;

    public Actors(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolves the current caller. Identities must be registered in app_user (mirrors Keycloak). */
    public Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiException(401, "CBR-AUT-000", "Authentication required");
        }
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean finance = roles.stream().anyMatch(FINANCE_ROLES::contains);
        return jdbc.sql("""
                        SELECT u.id, u.subject, u.kind, u.source_system_id, s.code
                          FROM app_user u
                          LEFT JOIN source_system s ON s.id = u.source_system_id AND s.status = 'ACTIVE'
                         WHERE u.subject = :subject AND u.status = 'ACTIVE'
                        """)
                .param("subject", auth.getName())
                .query((rs, n) -> new Actor(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), finance))
                .optional()
                .orElseThrow(() -> ApiException.forbidden("CBR-AUT-002", "Identity is not registered in CBRMS"));
    }

    public Actor system() {
        return new Actor(SYSTEM_USER_ID, "cbrms-system", "SYSTEM", null, null, false);
    }
}
